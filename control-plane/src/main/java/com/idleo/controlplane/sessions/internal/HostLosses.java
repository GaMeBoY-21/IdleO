package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.agentgateway.SessionReconciliation;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.sessions.SessionRequested;
import gpu.agent.v1.Session.RunningSession;
import gpu.agent.v1.Session.StopReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lost hosts (ADR 0016, technical design "Failure handling"). A host is lost when the attempt's lease has
 * lapsed (and the transit allowance passed, so the agent has certainly stopped), when its agent reports it
 * fenced or stopped the attempt, or when it reconnects without it. The session moves to HOST_LOST, then back
 * to the scheduler as its next attempt, keeping its one hold; the scheduler books it as before, and the new
 * attempt resumes from the newest complete checkpoint. After the configured number of lost attempts it
 * fails with {@code host_lost_repeatedly} instead.
 *
 * <p>Reconciliation on reconnect also lives here: a session this host should be running that its Hello
 * doesn't list is lost at once, and one it lists that the control plane has moved on from gets a
 * StopSession with reason SUPERSEDED.
 */
@Component
class HostLosses implements SessionReconciliation {

    private static final Logger log = LoggerFactory.getLogger(HostLosses.class);
    private static final int BATCH = 50;

    private final SessionStore store;
    private final SessionStops stops;
    private final CreditReservations reservations;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final LeaseProperties leases;
    private final MeterRegistry meters;
    private final int maxLostAttempts;

    HostLosses(
            SessionStore store,
            SessionStops stops,
            CreditReservations reservations,
            ApplicationEventPublisher events,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            LeaseProperties leases,
            MeterRegistry meters,
            @Value("${sessions.max-lost-attempts}") int maxLostAttempts) {
        this.store = store;
        this.stops = stops;
        this.reservations = reservations;
        this.events = events;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.leases = leases;
        this.meters = meters;
        this.maxLostAttempts = maxLostAttempts;
    }

    /** Sessions whose lease lapsed more than the transit allowance ago. Safe with several instances. */
    @Scheduled(fixedDelayString = "${sessions.leases.lapse-sweep-every}")
    void sweep() {
        try {
            transactions.executeWithoutResult(tx -> {
                var lapsed = jdbc.sql("""
                                select id from sessions.sessions
                                where state in ('PROVISIONING', 'RUNNING') and lease_expires_at <= :before
                                order by lease_expires_at
                                limit :limit
                                for update skip locked
                                """)
                        .param("before", Timestamp.from(store.now().minus(leases.transitAllowance())))
                        .param("limit", BATCH)
                        .query(UUID.class)
                        .list();
                lapsed.forEach(sessionId -> lost(sessionId, "lease_lapsed"));
            });
        } catch (RuntimeException e) {
            log.warn("The lapsed-lease sweep failed; it runs again shortly", e);
        }
    }

    /**
     * The session's current attempt lost its host, for {@code reason}: HOST_LOST, then REQUESTED as the next
     * attempt, or FAILED after too many. False, changing nothing, unless it was PROVISIONING or RUNNING.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean lost(UUID sessionId, String reason) {
        var session = store.lock(sessionId).orElse(null);
        if (session == null
                || store.transition(sessionId, SessionEvent.LEASE_LAPSED, reason, null, SessionStore.KEEP)
                        != SessionStore.Outcome.APPLIED) {
            return false;
        }
        meters.counter("sessions.hosts.lost", "reason", reason).increment();
        if (session.attempt() >= maxLostAttempts) {
            store.transition(sessionId, SessionEvent.LOST_REPEATEDLY, "host_lost_repeatedly", null, SessionStore.KEEP);
            jdbc.sql("update sessions.sessions set error_code = 'host_lost_repeatedly' where id = :id")
                    .param("id", sessionId)
                    .update();
            reservations.release(sessionId);
            log.warn("Session {} lost its host on attempt {} ({}): failed", sessionId, session.attempt(), reason);
            return true;
        }
        store.transition(sessionId, SessionEvent.REMATCH, "rematch", null, SessionStore.CLEAR);
        jdbc.sql("update sessions.sessions set attempt = attempt + 1, lease_expires_at = null where id = :id")
                .param("id", sessionId)
                .update();
        events.publishEvent(new SessionRequested(sessionId));
        log.info(
                "Session {} lost host {} on attempt {} ({}); matching attempt {}",
                sessionId,
                session.hostId(),
                session.attempt(),
                reason,
                session.attempt() + 1);
        return true;
    }

    @Override
    public void reconcile(UUID hostId, List<RunningSession> running, Instant connectedAt) {
        var listed = new HashMap<UUID, Integer>();
        for (var session : running) {
            try {
                listed.put(UUID.fromString(session.getSessionId()), session.getAttempt());
            } catch (IllegalArgumentException e) {
                log.warn("Host {} listed a running session with an invalid id; ignored", hostId);
            }
        }
        transactions.executeWithoutResult(tx -> {
            record Started(UUID id, int attempt) {}
            var ours = jdbc.sql("""
                            select s.id, s.attempt
                            from sessions.sessions s
                            join sessions.attempts a on a.session_id = s.id and a.attempt = s.attempt
                            where s.host_id = :hostId and s.state in ('PROVISIONING', 'RUNNING')
                              and a.start_delivered_at < :connectedAt
                            """)
                    .param("hostId", hostId)
                    .param("connectedAt", Timestamp.from(connectedAt))
                    .query((rs, n) -> new Started(rs.getObject("id", UUID.class), rs.getInt("attempt")))
                    .list();
            for (var session : ours) {
                var attempt = listed.get(session.id());
                if (attempt == null || attempt != session.attempt()) {
                    lost(session.id(), "missing_after_reconnect");
                }
            }
            listed.forEach((sessionId, attempt) -> {
                var current = store.find(sessionId)
                        .filter(session -> hostId.equals(session.hostId())
                                && session.attempt() == attempt
                                && (session.state() == SessionState.PROVISIONING
                                        || session.state() == SessionState.RUNNING
                                        || session.state() == SessionState.FINISHING))
                        .isPresent();
                if (!current) {
                    stops.queue(hostId, sessionId, attempt, StopReason.STOP_REASON_SUPERSEDED);
                    log.info("Host {} still runs session {} attempt {}; told to stop it", hostId, sessionId, attempt);
                }
            });
        });
    }
}
