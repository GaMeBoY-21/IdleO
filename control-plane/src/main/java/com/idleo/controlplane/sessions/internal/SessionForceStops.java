package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.identity.AuditTrail;
import gpu.agent.v1.Session.StopReason;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An admin stopping a running session (ADR 0027), through the same stop as a renter's or a credit stop: RUNNING to
 * FINISHING with the reason {@code operator_stopped}, and the agent told to stop at once, without a checkpoint. It
 * then completes and settles like any stopped session: the minutes it ran are charged, nothing more. The agent hears
 * {@code STOP_REASON_ABUSE}, the protocol's reason for a stop the platform decides. Audited.
 */
@Service
class SessionForceStops {

    private final RunningStops stops;
    private final SessionStore store;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;

    SessionForceStops(
            RunningStops stops, SessionStore store, AuditTrail audit, PlatformTransactionManager transactionManager) {
        this.stops = stops;
        this.store = store;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    enum Outcome {
        STOPPING,
        ALREADY_STOPPING,
        NOT_RUNNING,
        NO_SUCH_SESSION
    }

    Outcome stop(UUID sessionId, String reason, AuditTrail.Actor actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a force stop has a reason");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            var before = store.lock(sessionId).orElse(null);
            if (before == null) {
                return Outcome.NO_SUCH_SESSION;
            }
            var outcome = stops.stop(sessionId, "operator_stopped", StopReason.STOP_REASON_ABUSE, false);
            if (outcome == RunningStops.Outcome.STOPPING) {
                audit.record(new AuditTrail.Entry(
                        actor,
                        "session.force_stop",
                        "session",
                        sessionId.toString(),
                        reason,
                        Map.of("state", before.state().name(), "attempt", before.attempt()),
                        Map.of("state", SessionState.FINISHING.name(), "reason", "operator_stopped")));
            }
            return switch (outcome) {
                case STOPPING -> Outcome.STOPPING;
                case ALREADY_STOPPING -> Outcome.ALREADY_STOPPING;
                case NOT_RUNNING -> Outcome.NOT_RUNNING;
            };
        }));
    }
}
