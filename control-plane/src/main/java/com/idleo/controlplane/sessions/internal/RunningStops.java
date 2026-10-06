package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.SessionControl;
import gpu.agent.v1.Session.StopReason;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stopping a running attempt (ADR 0017), for its renter or for money. The session moves RUNNING to FINISHING
 * with the reason, and the agent gets a StopSession, acked, with a checkpoint first (unless the renter declined
 * one) and a 60-second grace period. The session completes, with reason {@code stopped}, when the agent reports
 * STOPPED, or once the grace and the lease after it have passed.
 */
@Component
class RunningStops implements SessionControl {

    private static final Logger log = LoggerFactory.getLogger(RunningStops.class);

    /** How long the agent may spend checkpointing before it stops anyway. */
    static final Duration GRACE = Duration.ofSeconds(60);

    private final SessionStore store;
    private final SessionStops stops;
    private final LeaseProperties leases;
    private final JdbcClient jdbc;

    RunningStops(SessionStore store, SessionStops stops, LeaseProperties leases, JdbcClient jdbc) {
        this.store = store;
        this.stops = stops;
        this.leases = leases;
        this.jdbc = jdbc;
    }

    enum Outcome {
        STOPPING,
        ALREADY_STOPPING,
        NOT_RUNNING
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean stopForCredit(UUID sessionId, CreditStop reason) {
        var why = reason == CreditStop.CREDIT_EXHAUSTED ? "credit_exhausted" : "max_spend_reached";
        return stop(sessionId, why, StopReason.STOP_REASON_CREDIT_EXHAUSTED, true) == Outcome.STOPPING;
    }

    /** Stops the session's running attempt; safe to repeat. The caller runs it in a transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    Outcome stop(UUID sessionId, String reason, StopReason agentReason, boolean checkpointFirst) {
        var session = store.lock(sessionId).orElse(null);
        if (session == null) {
            return Outcome.NOT_RUNNING;
        }
        if (session.state() == SessionState.FINISHING && stopRequested(sessionId, session.attempt())) {
            return Outcome.ALREADY_STOPPING;
        }
        if (session.state() != SessionState.RUNNING
                || store.transition(sessionId, SessionEvent.FINISH, reason, null, SessionStore.KEEP)
                        != SessionStore.Outcome.APPLIED) {
            return Outcome.NOT_RUNNING;
        }
        var grace = checkpointFirst ? GRACE : Duration.ZERO;
        var now = store.now();
        jdbc.sql("""
                        update sessions.attempts
                        set stop_requested_at = :now, stop_reason = :reason, stop_deadline = :deadline
                        where session_id = :sessionId and attempt = :attempt
                        """)
                .param("now", Timestamp.from(now))
                .param("reason", reason)
                .param(
                        "deadline",
                        Timestamp.from(now.plus(grace).plus(leases.ttl()).plus(leases.transitAllowance())))
                .param("sessionId", sessionId)
                .param("attempt", session.attempt())
                .update();
        stops.queue(
                Objects.requireNonNull(session.hostId(), "a running session has a host"),
                sessionId,
                session.attempt(),
                agentReason,
                checkpointFirst ? GRACE : null);
        log.info("Stopping session {} attempt {}: {}", sessionId, session.attempt(), reason);
        return Outcome.STOPPING;
    }

    boolean stopRequested(UUID sessionId, int attempt) {
        return jdbc.sql("""
                        select exists (select 1 from sessions.attempts
                                       where session_id = :sessionId and attempt = :attempt and stop_requested_at is not null)
                        """)
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .query(Boolean.class)
                .single();
    }
}
