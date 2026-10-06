package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.agentgateway.AgentStreams;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.LeaseRenew;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Renews the lease of every attempt that holds one (ADR 0016): the session is PROVISIONING or RUNNING, its
 * StartSession has been delivered, and its host's stream is live on this instance. Only the instance
 * holding a stream renews through it, so several instances never send twice. Each renewal moves {@code
 * sessions.lease_expires_at} to the send time plus the lease's length, as the agent counts from receipt.
 */
@Component
class LeaseRenewals {

    private static final Logger log = LoggerFactory.getLogger(LeaseRenewals.class);

    private final JdbcClient jdbc;
    private final AgentStreams streams;
    private final SessionStore store;
    private final LeaseProperties leases;
    private final LeaseBook leaseBook;
    private final TransactionTemplate transactions;
    private final Counter renewed;

    LeaseRenewals(
            JdbcClient jdbc,
            AgentStreams streams,
            SessionStore store,
            LeaseProperties leases,
            LeaseBook leaseBook,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.streams = streams;
        this.store = store;
        this.leases = leases;
        this.leaseBook = leaseBook;
        this.transactions = new TransactionTemplate(transactionManager);
        this.renewed = Counter.builder("sessions.leases.renewed")
                .description("LeaseRenew messages sent")
                .register(meters);
    }

    private record Lease(UUID sessionId, int attempt, UUID hostId) {}

    @Scheduled(fixedDelayString = "${sessions.leases.renew-every}")
    void renew() {
        try {
            var here = streams.liveHere();
            if (here.isEmpty()) {
                return;
            }
            var held = jdbc.sql("""
                            select s.id, s.attempt, s.host_id
                            from sessions.sessions s
                            join sessions.attempts a on a.session_id = s.id and a.attempt = s.attempt
                            where s.state in ('PROVISIONING', 'RUNNING') and a.start_delivered_at is not null
                              and s.host_id in (:hosts)
                            """)
                    .param("hosts", here)
                    .query((rs, n) -> new Lease(
                            rs.getObject("id", UUID.class), rs.getInt("attempt"), rs.getObject("host_id", UUID.class)))
                    .list();
            for (var lease : held) {
                var message = OpenStreamResponse.newBuilder()
                        .setLeaseRenew(LeaseRenew.newBuilder()
                                .setSessionId(lease.sessionId().toString())
                                .setAttempt(lease.attempt())
                                .setLeaseTtl(SessionSpecs.duration(leases.ttl())));
                if (!streams.sendIfLive(lease.hostId(), message)) {
                    continue;
                }
                renewed.increment();
                var sentAt = store.now();
                transactions.executeWithoutResult(tx -> {
                    if (jdbc.sql("""
                                            update sessions.sessions
                                            set lease_expires_at = greatest(lease_expires_at, :expiresAt)
                                            where id = :sessionId and attempt = :attempt and host_id = :hostId
                                              and state in ('PROVISIONING', 'RUNNING')
                                            """)
                                    .param("expiresAt", Timestamp.from(sentAt.plus(leases.ttl())))
                                    .param("sessionId", lease.sessionId())
                                    .param("attempt", lease.attempt())
                                    .param("hostId", lease.hostId())
                                    .update()
                            == 1) {
                        leaseBook.record(lease.sessionId(), lease.attempt(), sentAt, leases.ttl());
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("Lease renewal failed; it runs again shortly", e);
        }
    }
}
