package com.idleo.controlplane.sessions.internal;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A session in PROVISIONING that hasn't reached RUNNING in time fails with reason {@code start_timeout}:
 * nothing billed, hold released (technical design line 232, ADR 0015). It guards against agents that
 * accept and never start. Safe with several instances: each takes sessions the others haven't locked.
 */
@Component
class ProvisioningTimeouts {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningTimeouts.class);
    private static final int BATCH = 50;

    private final SessionStore store;
    private final SessionHolds holds;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Duration timeout;

    ProvisioningTimeouts(
            SessionStore store,
            SessionHolds holds,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${sessions.provisioning-timeout}") Duration timeout) {
        this.store = store;
        this.holds = holds;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.timeout = timeout;
    }

    @Scheduled(fixedDelayString = "${sessions.provisioning-sweep-interval}")
    void sweep() {
        try {
            transactions.executeWithoutResult(status -> {
                var stale = jdbc.sql("""
                                select id from sessions.sessions
                                where state = 'PROVISIONING' and state_changed_at <= :before
                                order by state_changed_at
                                limit :limit
                                for update skip locked
                                """)
                        .param("before", Timestamp.from(store.now().minus(timeout)))
                        .param("limit", BATCH)
                        .query(UUID.class)
                        .list();
                for (var sessionId : stale) {
                    if (store.transition(sessionId, SessionEvent.START_FAILED, "start_timeout", null, SessionStore.KEEP)
                            == SessionStore.Outcome.APPLIED) {
                        holds.releaseUnlessMetered(sessionId);
                        log.info("Session {} didn't start within {}: failed, hold released", sessionId, timeout);
                    }
                }
            });
        } catch (RuntimeException e) {
            log.warn("The provisioning timeout sweep failed; it runs again shortly", e);
        }
    }
}
