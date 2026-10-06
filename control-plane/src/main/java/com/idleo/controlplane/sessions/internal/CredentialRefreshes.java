package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.agentgateway.AgentCommands;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.RefreshCredentials;
import java.sql.Timestamp;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fresh storage credentials for long jobs (ADR 0007, ADR 0017). Once an attempt's grants pass half their lifetime,
 * a RefreshCredentials is queued, acked, with its credentials minted at each delivery like a StartSession's, so no
 * secret is stored. A host that was away gets it straight after it reconnects, with whatever else waited. One
 * refresh is queued at a time; it expires after half a lifetime, and another follows if the grants still need it.
 */
@Component
class CredentialRefreshes {

    static final String COMMAND_TYPE = "refresh_credentials";
    private static final Logger log = LoggerFactory.getLogger(CredentialRefreshes.class);
    private static final int BATCH = 100;

    private final JdbcClient jdbc;
    private final AgentCommands commands;
    private final SessionStore store;
    private final StorageProperties storage;
    private final TransactionTemplate transactions;

    CredentialRefreshes(
            JdbcClient jdbc,
            AgentCommands commands,
            SessionStore store,
            StorageProperties storage,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.commands = commands;
        this.store = store;
        this.storage = storage;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    private record Due(UUID sessionId, int attempt, UUID hostId) {}

    @Scheduled(fixedDelayString = "${sessions.storage.refresh-check-every}")
    void queueDue() {
        try {
            transactions.executeWithoutResult(tx -> {
                var now = store.now();
                var half = storage.grantTtl().dividedBy(2);
                var due = jdbc.sql("""
                                select a.session_id, a.attempt, a.host_id
                                from sessions.attempts a
                                join sessions.sessions s on s.id = a.session_id and s.attempt = a.attempt
                                where s.state in ('PROVISIONING', 'RUNNING', 'FINISHING') and a.ended_at is null
                                  and a.grants_minted_at <= :halfLifeAgo
                                  and (a.refresh_queued_at is null or a.refresh_queued_at <= :halfLifeAgo)
                                limit :limit
                                for update of a skip locked
                                """)
                        .param("halfLifeAgo", Timestamp.from(now.minus(half)))
                        .param("limit", BATCH)
                        .query((rs, n) -> new Due(
                                rs.getObject("session_id", UUID.class),
                                rs.getInt("attempt"),
                                rs.getObject("host_id", UUID.class)))
                        .list();
                for (var attempt : due) {
                    commands.enqueue(
                            attempt.hostId(),
                            attempt.sessionId(),
                            COMMAND_TYPE,
                            OpenStreamResponse.newBuilder()
                                    .setRefreshCredentials(RefreshCredentials.newBuilder()
                                            .setSessionId(attempt.sessionId().toString())
                                            .setAttempt(attempt.attempt())),
                            now.plus(half),
                            true);
                    jdbc.sql("""
                                    update sessions.attempts set refresh_queued_at = :now
                                    where session_id = :sessionId and attempt = :attempt
                                    """)
                            .param("now", Timestamp.from(now))
                            .param("sessionId", attempt.sessionId())
                            .param("attempt", attempt.attempt())
                            .update();
                    log.info(
                            "Queued fresh credentials for session {} attempt {}",
                            attempt.sessionId(),
                            attempt.attempt());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Queueing credential refreshes failed; it runs again shortly", e);
        }
    }
}
