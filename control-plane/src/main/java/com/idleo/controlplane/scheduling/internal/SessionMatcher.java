package com.idleo.controlplane.scheduling.internal;

import com.google.protobuf.Duration;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.agentgateway.AgentCommands;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.hosts.SchedulableHosts;
import com.idleo.controlplane.sessions.SessionBookings;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.Offer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Matches a REQUESTED session to a host. Candidates are tried in
 * score order, each in its own short transaction that locks the host's row (skipping a host another
 * booking holds), moves the session to MATCHED guarded by its version, holds 30 minutes of credit, and
 * queues the offer for the host's agent. If a step changes nothing, that transaction rolls back and the
 * next candidate is tried; the one-live-session index is the second guard against double booking.
 */
@Component
@EnableConfigurationProperties(SchedulingProperties.class)
class SessionMatcher {

    private static final Logger log = LoggerFactory.getLogger(SessionMatcher.class);

    private final SessionBookings sessions;
    private final SchedulableHosts hosts;
    private final CreditReservations reservations;
    private final AgentCommands commands;
    private final CandidateFinder candidates;
    private final OfferStore offers;
    private final SchedulingProperties properties;
    private final SchedulingMetrics metrics;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final TaskScheduler scheduler;

    /**
     * When presence couldn't be read, the match is tried again after each of these, then left to the re-match loop
     * (BookingJobs): a Valkey blip never tells a renter no GPU was free.
     */
    static final List<java.time.Duration> PRESENCE_RETRIES = List.of(
            java.time.Duration.ofSeconds(1),
            java.time.Duration.ofSeconds(2),
            java.time.Duration.ofSeconds(4),
            java.time.Duration.ofSeconds(8));

    /** The tries used so far, by session, while presence is unknown. */
    private final ConcurrentHashMap<UUID, Integer> presenceRetries = new ConcurrentHashMap<>();

    SessionMatcher(
            SessionBookings sessions,
            SchedulableHosts hosts,
            CreditReservations reservations,
            AgentCommands commands,
            CandidateFinder candidates,
            OfferStore offers,
            SchedulingProperties properties,
            SchedulingMetrics metrics,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            Clock clock,
            TaskScheduler scheduler) {
        this.sessions = sessions;
        this.hosts = hosts;
        this.reservations = reservations;
        this.commands = commands;
        this.candidates = candidates;
        this.offers = offers;
        this.properties = properties;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    /** What happened to one candidate. */
    private enum Attempt {
        MATCHED,
        /** Locked by another booking, no longer active, or now busy: try the next one. */
        NEXT,
        /** The session changed under us (cancelled, or matched elsewhere): stop. */
        SESSION_MOVED,
        /** The renter's credit no longer covers the hold. */
        NO_CREDIT
    }

    /** Rolls a candidate's transaction back with the reason. */
    private static final class Rollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Attempt attempt;

        Rollback(Attempt attempt) {
            super(attempt.name(), null, false, false);
            this.attempt = attempt;
        }
    }

    void match(UUID sessionId) {
        long started = System.nanoTime();
        var request = sessions.bookingRequest(sessionId).orElse(null);
        if (request == null || !request.state().equals("REQUESTED")) {
            presenceRetries.remove(sessionId);
            return;
        }
        if (offers.count(sessionId, request.attempt()) >= properties.maxOffers()) {
            endWithoutHost(sessionId, "offers_exhausted");
            return;
        }
        var found = candidates.find(request, offers.hostsOffered(sessionId, request.attempt()));
        var ranked = found.ranked();
        for (var scored : ranked) {
            var attempt = tryCandidate(request, scored.candidate());
            switch (attempt) {
                case MATCHED -> {
                    presenceRetries.remove(sessionId);
                    metrics.matched(java.time.Duration.ofNanos(System.nanoTime() - started), ranked.size());
                    return;
                }
                case NEXT -> {}
                case SESSION_MOVED -> {
                    metrics.outcome("session_moved");
                    return;
                }
                case NO_CREDIT -> {
                    transactions.executeWithoutResult(status -> sessions.cancel(sessionId, "insufficient_credit"));
                    metrics.outcome("insufficient_credit");
                    return;
                }
            }
        }
        if (!found.presenceKnown()) {
            // Whether any host is online isn't known (Valkey didn't answer), so this says nothing about whether one is
            // free: the booking waits, still REQUESTED, and is tried again shortly.
            metrics.outcome("presence_unknown");
            retryWhilePresenceUnknown(sessionId);
            return;
        }
        presenceRetries.remove(sessionId);
        if (request.attempt() > 1) {
            // A session that already ran waits for a host rather than ending; the re-match retries try again.
            metrics.outcome("waiting_for_host");
            return;
        }
        endWithoutHost(sessionId, "no_candidate");
    }

    /**
     * Tries the match again after the next of {@link #PRESENCE_RETRIES}. Once they're used up, the re-match loop
     * (BookingJobs, SessionBookings.awaitingRematch) keeps trying a booking left REQUESTED, as it does for a re-match.
     */
    private void retryWhilePresenceUnknown(UUID sessionId) {
        int tries = presenceRetries.merge(sessionId, 1, Integer::sum);
        if (tries > PRESENCE_RETRIES.size()) {
            presenceRetries.remove(sessionId);
            log.info("Session {} waits for presence to come back; the re-match loop tries it", sessionId);
            return;
        }
        // The scheduler's own clock: a delay, not a business time.
        scheduler.schedule(
                () -> match(sessionId), scheduler.getClock().instant().plus(PRESENCE_RETRIES.get(tries - 1)));
    }

    private Attempt tryCandidate(SessionBookings.BookingRequest request, Scoring.Candidate candidate) {
        try {
            transactions.executeWithoutResult(status -> book(request, candidate));
            return Attempt.MATCHED;
        } catch (Rollback rollback) {
            return rollback.attempt;
        } catch (DataIntegrityViolationException busy) {
            // The one-live-session index: another session took this host first.
            return Attempt.NEXT;
        }
    }

    private void book(SessionBookings.BookingRequest request, Scoring.Candidate candidate) {
        if (!hosts.lockForBooking(candidate.hostId())) {
            throw new Rollback(Attempt.NEXT);
        }
        if (!sessions.match(request.sessionId(), request.version(), candidate.hostId(), candidate.pricePaisePerMin())) {
            throw new Rollback(Attempt.SESSION_MOVED);
        }
        var hold = reservations.holdFor(candidate.pricePaisePerMin(), Paise.of(request.maxSpendPaise()));
        if (!reservations.reserve(request.renterId(), request.sessionId(), hold)) {
            throw new Rollback(Attempt.NO_CREDIT);
        }
        var now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        var offer = new OfferStore.Offer(
                ids.newId(),
                request.sessionId(),
                candidate.hostId(),
                request.attempt(),
                candidate.gpuUuid(),
                offers.count(request.sessionId(), request.attempt()) + 1,
                "pending",
                null,
                now.plus(properties.offerTtl()),
                now);
        offers.insert(offer);
        commands.enqueue(
                candidate.hostId(),
                request.sessionId(),
                "offer",
                OpenStreamResponse.newBuilder().setOffer(offerMessage(offer.id(), request)),
                offer.expiresAt(),
                false);
        log.info(
                "Session {} matched to host {} at {} paise a minute, holding {} paise (offer {} of {})",
                request.sessionId(),
                candidate.hostId(),
                candidate.pricePaisePerMin(),
                hold.value(),
                offer.attemptNumber(),
                properties.maxOffers());
    }

    private Offer.Builder offerMessage(UUID offerId, SessionBookings.BookingRequest request) {
        return Offer.newBuilder()
                .setOfferId(offerId.toString())
                .setSpec(request.spec())
                .setExpiresIn(duration(properties.offerTtl()));
    }

    private static Duration duration(java.time.Duration value) {
        return Duration.newBuilder()
                .setSeconds(value.getSeconds())
                .setNanos(value.getNano())
                .build();
    }

    /** No host can take it: NO_HOST_FREE, with any hold released. */
    void endWithoutHost(UUID sessionId, String reason) {
        transactions.executeWithoutResult(status -> {
            if (sessions.noHostFree(sessionId, reason) && !sessions.hasRun(sessionId)) {
                reservations.release(sessionId);
            }
        });
        metrics.outcome("no_host_free");
    }
}
