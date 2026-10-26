package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.images.CheckedImages;
import com.idleo.controlplane.sessions.CreateSessionRequest;
import com.idleo.controlplane.sessions.PriceCeilings;
import com.idleo.controlplane.sessions.SessionCancelled;
import com.idleo.controlplane.sessions.SessionRequested;
import com.idleo.controlplane.sessions.SessionRequests;
import gpu.agent.v1.Session.StopReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Booking requests and cancellations from renters (ADR 0015). */
@Service
class SessionService {

    private final SessionStore store;
    private final SessionStops stops;
    private final RunningStops runningStops;
    private final SessionRequests checks;
    private final CreditReservations reservations;
    private final SessionHolds holds;
    private final PriceCeilings ceilings;
    private final UploadStore uploads;
    private final UploadProperties uploadProperties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;

    private final SessionLimits limits;
    private final CheckedImages images;

    SessionService(
            SessionStore store,
            SessionStops stops,
            RunningStops runningStops,
            SessionRequests checks,
            CreditReservations reservations,
            SessionHolds holds,
            PriceCeilings ceilings,
            UploadStore uploads,
            UploadProperties uploadProperties,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            SessionLimits limits,
            CheckedImages images) {
        this.limits = limits;
        this.images = images;
        this.store = store;
        this.stops = stops;
        this.runningStops = runningStops;
        this.checks = checks;
        this.reservations = reservations;
        this.holds = holds;
        this.ceilings = ceilings;
        this.uploads = uploads;
        this.uploadProperties = uploadProperties;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
    }

    /** The session, and whether this call created it ({@code false}: a repeat of the same request). */
    record Created(SessionStore.Session session, boolean created) {}

    /**
     * Creates a REQUESTED session, marks its uploads used, and publishes {@link SessionRequested}. Refused, before anything is
     * created, if the renter's available credit can't cover the hold the booking will need (30 minutes at
     * their maximum price, capped at their maximum spend). A booking that leaves its maximum price out gets the
     * highest price among the GPUs that fit right now (ADR 0021), or no-gpu-free when none does. The same key with
     * the same request returns the session it created; with a different request it's refused.
     */
    Created create(UUID renterId, String idempotencyKey, CreateSessionRequest request) {
        var checked = checks.check(renterId, request);
        var template = checked.template();
        int vram = checked.vramNeededMb();
        var maxRuntime = checked.maxRuntime();
        var hash = hash(
                request.templateId(),
                request.maxPricePaisePerMin(),
                request.maxSpendPaise(),
                vram,
                maxRuntime,
                checked);
        return Objects.requireNonNull(transactions.execute(status -> {
            var existing = store.findByKey(renterId, idempotencyKey);
            if (existing.isPresent()) {
                return repeat(existing.get(), hash);
            }
            limits.checkNewSession(renterId);
            int ceiling = ceilings.ceiling(
                    request.maxPricePaisePerMin(),
                    vram,
                    checked.diskNeededMb(),
                    checked.image() == null ? null : checked.image().cudaNeeded());
            var hold = reservations.holdFor(ceiling, Paise.of(request.maxSpendPaise()));
            var available = reservations.available(renterId);
            if (hold.isGreaterThan(available)) {
                throw ApiProblem.of(
                        Problem.INSUFFICIENT_CREDIT,
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
                    // A renter's own image runs as checked, pinned by digest (ADR 0042); a new push to its tag never
                    // changes it.
                    checked.image() == null
                            ? Objects.requireNonNull(template.image())
                            : checked.image().reference(),
                    vram,
                    ceiling,
                    null,
                    request.maxSpendPaise(),
                    Math.toIntExact(maxRuntime.toSeconds()),
                    idempotencyKey,
                    hash,
                    now,
                    0,
                    now,
                    null,
                    null,
                    checked.params(),
                    checked.presets(),
                    checked.inputs().stream()
                            .map(input -> new SessionStore.Input(input.name(), input.uploadId(), input.url()))
                            .toList(),
                    checked.image() == null ? null : checked.image().id());
            if (!store.insert(session)) {
                // A concurrent request with the same key got there first.
                return repeat(store.findByKey(renterId, idempotencyKey).orElseThrow(), hash);
            }
            // Its uploads were just used: each is kept 30 days from now (ADR 0022).
            uploads.used(session.uploadIds(), now, uploadProperties.readyTtl());
            if (session.imageId() != null) {
                images.used(session.imageId());
            }
            events.publishEvent(new SessionRequested(session.id()));
            return new Created(session, true);
        }));
    }

    private static Created repeat(SessionStore.Session existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw ApiProblem.of(
                    Problem.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different session request.");
        }
        return new Created(existing, false);
    }

    /**
     * Stops the renter's running session (ADR 0017): RUNNING to FINISHING, and its agent told to stop, after a
     * checkpoint unless {@code checkpointFirst} is false. It completes, with reason {@code stopped}, when the agent
     * reports it stopped. Repeating it changes nothing. A session that hasn't started running is cancelled
     * instead, and one that has ended can't be stopped: both are 409.
     */
    SessionStore.Session stop(UUID renterId, UUID sessionId, boolean checkpointFirst) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var session = owned(renterId, sessionId);
            var outcome = runningStops.stop(
                    sessionId, "renter_stopped", StopReason.STOP_REASON_RENTER_REQUESTED, checkpointFirst);
            if (outcome == RunningStops.Outcome.NOT_RUNNING) {
                var notYet = session.state() == SessionState.REQUESTED
                        || session.state() == SessionState.MATCHED
                        || session.state() == SessionState.PROVISIONING;
                throw ApiProblem.of(
                        Problem.SESSION_NOT_RUNNING,
                        notYet
                                ? "This session hasn't started running: cancel it with POST /v1/sessions/" + sessionId
                                        + "/cancel."
                                : "A session can't be stopped once it's " + session.state() + ".");
            }
            return store.find(sessionId).orElseThrow();
        }));
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
            if (!cancelled(session, "renter_cancelled")) {
                throw ApiProblem.of(
                        Problem.SESSION_NOT_CANCELLABLE,
                        "A session can't be cancelled once it's " + session.state() + ".");
            }
            return store.find(sessionId).orElseThrow();
        }));
    }

    /**
     * Cancels a session that hasn't run, for {@code reason}, in the caller's transaction: its hold released, nothing billed,
     * and an agent that may have started it told to stop. False, changing nothing, once it's running or over.
     */
    boolean cancelled(SessionStore.Session session, String reason) {
        var sessionId = session.id();
        var outcome = store.transition(sessionId, SessionEvent.CANCEL, reason, null, SessionStore.KEEP);
        if (outcome != SessionStore.Outcome.APPLIED) {
            return false;
        }
        store.endAttempt(sessionId, session.attempt());
        holds.releaseUnlessMetered(sessionId);
        if (session.state() == SessionState.PROVISIONING && session.hostId() != null && startDelivered(session)) {
            // The agent may have started it: tell it to stop rather than wait for the lease.
            stops.queue(session.hostId(), sessionId, session.attempt(), StopReason.STOP_REASON_RENTER_REQUESTED);
        }
        events.publishEvent(new SessionCancelled(sessionId));
        return true;
    }

    private boolean startDelivered(SessionStore.Session session) {
        return store.startDelivered(session.id(), session.attempt());
    }

    SessionStore.Session owned(UUID renterId, UUID sessionId) {
        return store.find(sessionId)
                .filter(session -> session.renterId().equals(renterId))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such session."));
    }

    /** The request's fingerprint: what it books, its resolved parameters and presets, and its inputs. */
    private static String hash(
            String templateId,
            @org.jspecify.annotations.Nullable Integer maxPrice,
            long maxSpend,
            int vram,
            Duration maxRuntime,
            SessionRequests.Checked checked) {
        var params = new StringBuilder();
        checked.params()
                .forEach((name, value) -> params.append(name)
                        .append('=')
                        .append(SessionParams.render(value))
                        .append('\u0000'));
        var canonical = String.join(
                        "|",
                        templateId,
                        // A ceiling left to the server is part of the request as "server", so a retry matches whatever
                        // ceiling the first attempt got.
                        maxPrice == null ? "server" : Integer.toString(maxPrice),
                        Long.toString(maxSpend),
                        Integer.toString(vram),
                        Long.toString(maxRuntime.toSeconds()),
                        params,
                        checked.presets().toString(),
                        checked.inputs().stream()
                                .map(input -> input.name() + "=" + input.uploadId() + "," + input.url())
                                .toList()
                                .toString())
                // Only a session of the renter's own image names one, so every other request's fingerprint is as it
                // was.
                + (checked.image() == null ? "" : "|image=" + checked.image().id());
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
