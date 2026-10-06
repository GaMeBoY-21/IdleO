package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.sessions.MeteredSessions;
import com.idleo.controlplane.sessions.SessionEnded;
import com.idleo.controlplane.sessions.SessionStarted;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How metering follows sessions (ADR 0017): from durable events (a session's minute 0, and its end), and a tick
 * that decides each open session's minutes. Each session is handled under its own row lock, skipping one another
 * instance holds, so several instances never meter one session twice.
 */
@Component
class MeteringJobs {

    private static final Logger log = LoggerFactory.getLogger(MeteringJobs.class);

    private final MeteredSessions sessions;
    private final Witnesses witnesses;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    MeteringJobs(
            MeteredSessions sessions,
            Witnesses witnesses,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.sessions = sessions;
        this.witnesses = witnesses;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener
    void on(SessionStarted event) {
        transactions.executeWithoutResult(tx -> track(event.sessionId()));
    }

    @Async
    @TransactionalEventListener
    void on(SessionEnded event) {
        transactions.executeWithoutResult(tx -> {
            track(event.sessionId());
            jdbc.sql("""
                            update metering.metered_sessions set ended_at = coalesce(ended_at, :now)
                            where session_id = :sessionId
                            """)
                    .param("now", Timestamp.from(now()))
                    .param("sessionId", event.sessionId())
                    .update();
        });
    }

    /** Starts metering a session that has a minute 0; doing it again changes nothing. */
    private void track(UUID sessionId) {
        var session = sessions.session(sessionId).orElse(null);
        if (session == null || session.startedAt() == null) {
            return;
        }
        jdbc.sql("""
                        insert into metering.metered_sessions (session_id, renter_id, started_at, max_spend_paise, created_at)
                        values (:sessionId, :renterId, :startedAt, :maxSpend, :now)
                        on conflict do nothing
                        """)
                .param("sessionId", sessionId)
                .param("renterId", session.renterId())
                .param("startedAt", Timestamp.from(session.startedAt()))
                .param("maxSpend", session.maxSpendPaise())
                .param("now", Timestamp.from(now()))
                .update();
    }

    @Scheduled(fixedDelayString = "${metering.tick-every}")
    void tick() {
        try {
            var open = jdbc.sql("select session_id from metering.metered_sessions where closed_at is null")
                    .query(UUID.class)
                    .list();
            for (var sessionId : open) {
                transactions.executeWithoutResult(tx -> meter(sessionId));
            }
        } catch (RuntimeException e) {
            log.warn("The metering tick failed; it runs again shortly", e);
        }
    }

    /** One session's turn, under its row lock; skipped if another instance has it. */
    void meter(UUID sessionId) {
        var startedAt = jdbc.sql("""
                        select started_at from metering.metered_sessions
                        where session_id = :sessionId and closed_at is null
                        for update skip locked
                        """)
                .param("sessionId", sessionId)
                .query(Timestamp.class)
                .optional();
        if (startedAt.isEmpty()) {
            return;
        }
        witnesses.decide(sessionId, startedAt.get().toInstant(), now());
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
