package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.sessions.SessionCancelled;
import com.idleo.controlplane.sessions.SessionRequested;
import gpu.agent.v1.Session.StopReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Booking requests and cancellations from renters (ADR 0015). */
@Service
class SessionService {

    private final SessionStore store;
    private final SessionStops stops;
    private final TemplateCatalog catalog;
    private final CreditReservations reservations;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;

    SessionService(
            SessionStore store,
            SessionStops stops,
            TemplateCatalog catalog,
            CreditReservations reservations,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            IdGenerator ids) {
        this.store = store;
        this.stops = stops;
        this.catalog = catalog;
        this.reservations = reservations;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
    }

    record Request(
            String templateId,
            int maxPricePaisePerMin,
            long maxSpendPaise,
            @Nullable Integer vramNeededMb,
            @Nullable Integer maxRuntimeSeconds) {}

    /** The session, and whether this call created it ({@code false}: a repeat of the same request). */
    record Created(SessionStore.Session session, boolean created) {}

    /**
     * Creates a REQUESTED session and publishes {@link SessionRequested}. Refused, before anything is
     * created, if the renter's available credit can't cover the hold the booking will need (30 minutes at
     * their maximum price, capped at their maximum spend). The same key with the same request returns the
     * session it created; with a different request it's refused.
     */
    Created create(UUID renterId, String idempotencyKey, Request request) {
        var template = catalog.find(request.templateId())
                .orElseThrow(() -> badRequest("There is no template " + request.templateId() + "."));
        int vram = request.vramNeededMb() == null ? template.minVramMb() : request.vramNeededMb();
        if (vram < template.minVramMb()) {
            throw badRequest("vramNeededMb must be at least the template's " + template.minVramMb() + " MB.");
        }
        var maxRuntime = request.maxRuntimeSeconds() == null
                ? template.defaultMaxRuntime()
                : Duration.ofSeconds(request.maxRuntimeSeconds());
        if (maxRuntime.compareTo(template.maxRuntimeCap()) > 0) {
            throw badRequest("maxRuntimeSeconds must be at most the template's "
                    + template.maxRuntimeCap().toSeconds() + " seconds.");
        }
        var hash = hash(request.templateId(), request.maxPricePaisePerMin(), request.maxSpendPaise(), vram, maxRuntime);
        return Objects.requireNonNull(transactions.execute(status -> {
            var existing = store.findByKey(renterId, idempotencyKey);
            if (existing.isPresent()) {
                return repeat(existing.get(), hash);
            }
            var hold = CreditReservations.holdFor(request.maxPricePaisePerMin(), Paise.of(request.maxSpendPaise()));
            var available = reservations.available(renterId);
            if (hold.isGreaterThan(available)) {
                throw new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "Booking holds " + hold.value() + " paise (30 minutes at your maximum price, up to your "
                                + "maximum spend), but only " + available.value() + " paise of credit is available.");
            }
            var now = store.now();
            var session = new SessionStore.Session(
                    ids.newId(),
                    renterId,
                    null,
                    "batch",
                    SessionState.REQUESTED,
                    1,
                    template.id(),
                    template.image(),
                    vram,
                    request.maxPricePaisePerMin(),
                    null,
                    request.maxSpendPaise(),
                    Math.toIntExact(maxRuntime.toSeconds()),
                    idempotencyKey,
                    hash,
                    now,
                    0,
                    now,
                    null,
                    null);
            if (!store.insert(session)) {
                // A concurrent request with the same key got there first.
                return repeat(store.findByKey(renterId, idempotencyKey).orElseThrow(), hash);
            }
            events.publishEvent(new SessionRequested(session.id()));
            return new Created(session, true);
        }));
    }

    private static Created repeat(SessionStore.Session existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "This Idempotency-Key was already used for a different session request.");
        }
        return new Created(existing, false);
    }

    /**
     * Cancels a session that hasn't run (REQUESTED, MATCHED or PROVISIONING): its hold is released, nothing
     * is billed, and any open offer is withdrawn. Cancelling a cancelled session changes nothing.
     */
    SessionStore.Session cancel(UUID renterId, UUID sessionId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var session = owned(renterId, sessionId);
            if (session.state() == SessionState.CANCELLED) {
                return session;
            }
            var outcome = store.transition(sessionId, SessionEvent.CANCEL, "renter_cancelled", null, SessionStore.KEEP);
            if (outcome != SessionStore.Outcome.APPLIED) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "A session can't be cancelled once it's " + session.state() + ".");
            }
            store.endAttempt(sessionId, session.attempt());
            reservations.release(sessionId);
            if (session.state() == SessionState.PROVISIONING && session.hostId() != null && startDelivered(session)) {
                // The agent may have started it: tell it to stop rather than wait for the lease.
                stops.queue(session.hostId(), sessionId, session.attempt(), StopReason.STOP_REASON_RENTER_REQUESTED);
            }
            events.publishEvent(new SessionCancelled(sessionId));
            return store.find(sessionId).orElseThrow();
        }));
    }

    private boolean startDelivered(SessionStore.Session session) {
        return store.startDelivered(session.id(), session.attempt());
    }

    SessionStore.Session owned(UUID renterId, UUID sessionId) {
        return store.find(sessionId)
                .filter(session -> session.renterId().equals(renterId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String hash(String templateId, int maxPrice, long maxSpend, int vram, Duration maxRuntime) {
        var canonical = String.join(
                "|",
                templateId,
                Integer.toString(maxPrice),
                Long.toString(maxSpend),
                Integer.toString(vram),
                Long.toString(maxRuntime.toSeconds()));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
