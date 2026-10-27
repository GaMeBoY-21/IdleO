package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.images.CheckedImages;
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
import org.springframework.util.unit.DataSize;

/**
 * A session in PROVISIONING that hasn't reached RUNNING in time fails with reason {@code start_timeout}:
 * nothing billed, hold released. It guards against agents that
 * accept and never start. The time grows with the uploaded inputs, which the agent downloads before the job starts
 * (ADR 0019, ADR 0022): the base, plus all their sizes together at the staging rate, so a large model on home
 * internet isn't taken for a failed start; a session of the renter's own image has {@code images.pull-time-limit} more
 * for the host to download it (ADR 0042), after which the host's runner has failed it itself. Safe with several
 * instances: each takes sessions the others haven't locked.
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
    private final long stagingBytesPerSecond;
    private final Duration pullTimeLimit;

    ProvisioningTimeouts(
            SessionStore store,
            SessionHolds holds,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${sessions.provisioning-timeout}") Duration timeout,
            @Value("${sessions.input-staging-rate}") DataSize stagingRate,
            CheckedImages images) {
        this.pullTimeLimit = images.pullTimeLimit();
        this.store = store;
        this.holds = holds;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.timeout = timeout;
        this.stagingBytesPerSecond = stagingRate.toBytes();
        if (stagingBytesPerSecond <= 0) {
            throw new IllegalStateException("sessions.input-staging-rate must be above zero");
        }
    }

    @Scheduled(fixedDelayString = "${sessions.provisioning-sweep-interval}")
    void sweep() {
        try {
            transactions.executeWithoutResult(status -> {
                var stale = jdbc.sql("""
                                select s.id from sessions.sessions s
                                where s.state = 'PROVISIONING'
                                  and s.state_changed_at
                                      + make_interval(secs => :baseSeconds
                                          + case when s.image_id is null then 0 else :pullSeconds end
                                          + (select coalesce(sum(u.size_bytes), 0)
                                             from sessions.session_inputs i
                                             join sessions.uploads u on u.id = i.upload_id
                                             where i.session_id = s.id) / :bytesPerSecond)
                                      <= :now
                                order by s.state_changed_at
                                limit :limit
                                for update of s skip locked
                                """)
                        .param("baseSeconds", (double) timeout.toMillis() / 1000)
                        .param("pullSeconds", (double) pullTimeLimit.toMillis() / 1000)
                        .param("bytesPerSecond", (double) stagingBytesPerSecond)
                        .param("now", Timestamp.from(store.now()))
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
