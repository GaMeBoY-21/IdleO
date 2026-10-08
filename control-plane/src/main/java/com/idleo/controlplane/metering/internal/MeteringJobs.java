package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.sessions.AttemptLost;
import com.idleo.controlplane.sessions.MeteredSessions;
import com.idleo.controlplane.sessions.SessionEnded;
import com.idleo.controlplane.sessions.SessionStarted;
import com.idleo.controlplane.sessions.SessionUpdates;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How metering follows sessions (ADR 0017): from durable events (a session's minute 0, and its end), and a tick
 * that decides each open session's minutes, settles every 15-minute window, keeps its credit ahead while it runs,
 * and closes it once it ends. Each session is handled under its own row lock, skipping one another
 * instance holds, so several instances never meter one session twice.
 */
@Component
class MeteringJobs {

    private static final Logger log = LoggerFactory.getLogger(MeteringJobs.class);

    private final MeteredSessions sessions;
    private final Witnesses witnesses;
    private final Settlements settlements;
    private final CreditGuard credit;
    private final Waivers waivers;
    private final SessionUpdates updates;
    private final CreditReservations reservations;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration window;
    private final Duration reviewAfter;

    MeteringJobs(
            MeteredSessions sessions,
            Witnesses witnesses,
            Settlements settlements,
            CreditGuard credit,
            Waivers waivers,
            CreditReservations reservations,
            SessionUpdates updates,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${metering.settlement-window}") Duration window,
            @Value("${metering.review-after}") Duration reviewAfter) {
        this.sessions = sessions;
        this.witnesses = witnesses;
        this.settlements = settlements;
        this.credit = credit;
        this.waivers = waivers;
        this.reservations = reservations;
        this.updates = updates;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.window = window;
        this.reviewAfter = reviewAfter;
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

    /**
     * An attempt lost its host: its minutes after its newest complete checkpoint are given back. Storage is asked
     * for that checkpoint first, outside the transaction; if storage can't answer, the event is retried.
     */
    @Async
    @TransactionalEventListener
    void on(AttemptLost event) {
        var checkpointAt = sessions.lastCompleteCheckpointAt(event.sessionId(), event.attempt());
        transactions.executeWithoutResult(tx -> {
            track(event.sessionId());
            waivers.waive(event.sessionId(), event.attempt(), checkpointAt.orElse(null), now());
        });
    }

    /** Starts metering a session that has a minute 0; doing it again changes nothing. */
    private void track(UUID sessionId) {
        var session = sessions.session(sessionId).orElse(null);
        if (session == null || session.startedAt() == null) {
            return;
        }
        jdbc.sql("""
                        insert into metering.metered_sessions
                            (session_id, renter_id, started_at, max_spend_paise, next_window_at, created_at)
                        values (:sessionId, :renterId, :startedAt, :maxSpend, :nextWindow, :now)
                        on conflict do nothing
                        """)
                .param("sessionId", sessionId)
                .param("renterId", session.renterId())
                .param("startedAt", Timestamp.from(session.startedAt()))
                .param("maxSpend", session.maxSpendPaise())
                .param("nextWindow", Timestamp.from(session.startedAt().plus(window)))
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

    private record Row(
            UUID renterId,
            long maxSpendPaise,
            Instant startedAt,
            int settlements,
            Instant nextWindowAt,
            @Nullable Instant endedAt) {}

    /**
     * One session's turn, under its row lock; skipped if another instance has it: decide its minutes, settle
     * every window that has ended, and close it once it has ended and its minutes are settled or waited for.
     */
    void meter(UUID sessionId) {
        var row = lock(sessionId);
        if (row == null) {
            return;
        }
        var now = now();
        witnesses.decide(sessionId, row.startedAt(), now);
        waivers.waiveLateMinutes(sessionId);
        var nextWindowAt = row.nextWindowAt();
        while (!nextWindowAt.isAfter(now)) {
            settlements.settle(metered(sessionId), nextWindowAt, now);
            updates.changed(sessionId);
            nextWindowAt = nextWindowAt.plus(window);
            jdbc.sql("update metering.metered_sessions set next_window_at = :next where session_id = :sessionId")
                    .param("next", Timestamp.from(nextWindowAt))
                    .param("sessionId", sessionId)
                    .update();
        }
        var endedAt = row.endedAt();
        if (endedAt == null) {
            credit.check(metered(sessionId), now, () -> metered(sessionId));
        } else if (allDecided(sessionId) || !endedAt.plus(reviewAfter).isAfter(now)) {
            close(sessionId, now);
        }
    }

    /**
     * The session has ended: settle everything both witnesses saw, send whatever only one saw (or couldn't be
     * paid for) to review, release the hold, and stop metering it.
     */
    private void close(UUID sessionId, Instant now) {
        settlements.settle(metered(sessionId), null, now);
        int reviewed = jdbc.sql("""
                        insert into metering.review_minutes (session_id, minute_index, minute_at, attempt, reason, created_at)
                        select session_id, minute_index, minute_at, coalesce(agent_attempt, lease_attempt),
                               case when agent_reported and lease_alive and agent_attempt = lease_attempt then 'over_limit'
                                    when agent_reported then 'agent_only'
                                    else 'lease_only' end,
                               :now
                        from metering.usage_minutes
                        where session_id = :sessionId and not billed and not waived and (agent_reported or lease_alive)
                        on conflict do nothing
                        """)
                .param("sessionId", sessionId)
                .param("now", Timestamp.from(now))
                .update();
        reservations.release(sessionId);
        // Its last charge is posted and its hold released: the session is settled.
        updates.changed(sessionId);
        jdbc.sql("update metering.metered_sessions set closed_at = :now where session_id = :sessionId")
                .param("now", Timestamp.from(now))
                .param("sessionId", sessionId)
                .update();
        log.info("Closed session {}'s usage; {} minutes went to review", sessionId, reviewed);
    }

    /** Every minute either witness saw is billable, billed or waived: nothing waits on the other witness. */
    private boolean allDecided(UUID sessionId) {
        return !jdbc.sql("""
                        select exists (
                            select 1 from metering.usage_minutes
                            where session_id = :sessionId and not billed and not waived
                              and (agent_reported or lease_alive)
                              and not (agent_reported and lease_alive and agent_attempt = lease_attempt))
                        """).param("sessionId", sessionId).query(Boolean.class).single();
    }

    private @Nullable Row lock(UUID sessionId) {
        return jdbc.sql("""
                        select renter_id, max_spend_paise, started_at, settlements, next_window_at, ended_at
                        from metering.metered_sessions
                        where session_id = :sessionId and closed_at is null
                        for update skip locked
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Row(
                        rs.getObject("renter_id", UUID.class),
                        rs.getLong("max_spend_paise"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getInt("settlements"),
                        rs.getTimestamp("next_window_at").toInstant(),
                        rs.getTimestamp("ended_at") == null
                                ? null
                                : rs.getTimestamp("ended_at").toInstant()))
                .optional()
                .orElse(null);
    }

    /** The session as a settlement needs it, read again, since each settlement counts. */
    private Settlements.Metered metered(UUID sessionId) {
        return jdbc.sql("""
                        select renter_id, max_spend_paise, settlements from metering.metered_sessions
                        where session_id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Settlements.Metered(
                        sessionId,
                        rs.getObject("renter_id", UUID.class),
                        rs.getLong("max_spend_paise"),
                        rs.getInt("settlements")))
                .single();
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
