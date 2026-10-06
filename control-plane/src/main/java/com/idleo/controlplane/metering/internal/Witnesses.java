package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.sessions.MeteredSessions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second witness (ADR 0017): {@code lease_alive}, decided only from the control plane's own lease record,
 * never from anything the agent says. An attempt's minute k is the stretch from its first RUNNING (as the control
 * plane received it) plus k minutes to one minute later. It's alive only if one unbroken stretch of the lease
 * covered all of it. The control plane writes a row for every whole minute it saw the attempt running; the last,
 * partial minute exists only if the agent reports it, and then the lease must cover it up to the attempt's end.
 */
@Component
class Witnesses {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final MeteredSessions sessions;
    private final JdbcClient jdbc;

    Witnesses(MeteredSessions sessions, JdbcClient jdbc) {
        this.sessions = sessions;
        this.jdbc = jdbc;
    }

    /** Decides every minute of the session that can be decided at {@code now}. The caller holds its row lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    void decide(UUID sessionId, Instant startedAt, Instant now) {
        for (var attempt : sessions.attempts(sessionId)) {
            var runningAt = attempt.runningAt();
            if (runningAt == null) {
                continue;
            }
            var endedAt = attempt.endedAt();
            var horizon = endedAt == null || endedAt.isAfter(now) ? now : endedAt;
            long wholeMinutes = Math.max(0, Duration.between(runningAt, horizon).toMinutes());
            long checked = checkedThrough(sessionId, attempt.attempt());
            for (long k = checked + 1; k < wholeMinutes; k++) {
                var from = runningAt.plus(MINUTE.multipliedBy(k));
                boolean covered = sessions.leaseCovered(sessionId, attempt.attempt(), from, from.plus(MINUTE));
                int index = Math.toIntExact(attempt.minuteIndexBase() + k);
                lease(sessionId, index, startedAt.plus(MINUTE.multipliedBy(index)), covered, attempt.attempt());
            }
            if (wholeMinutes - 1 > checked) {
                jdbc.sql("""
                                update metering.metered_attempts set checked_through = :checked
                                where session_id = :sessionId and attempt = :attempt
                                """)
                        .param("checked", wholeMinutes - 1)
                        .param("sessionId", sessionId)
                        .param("attempt", attempt.attempt())
                        .update();
            }
            if (endedAt != null && !endedAt.isAfter(now)) {
                decideReportedTail(sessionId, attempt, runningAt, endedAt, wholeMinutes);
            }
        }
    }

    /**
     * After an attempt ended: the minutes its agent reported beyond the whole ones. The one straddling the end is
     * alive if the lease covered it up to the end; anything later never ran inside a lease.
     */
    private void decideReportedTail(
            UUID sessionId, MeteredSessions.Attempt attempt, Instant runningAt, Instant endedAt, long wholeMinutes) {
        int partialIndex = Math.toIntExact(attempt.minuteIndexBase() + wholeMinutes);
        var partialFrom = runningAt.plus(MINUTE.multipliedBy(wholeMinutes));
        boolean partialCovered = endedAt.isAfter(partialFrom)
                && sessions.leaseCovered(sessionId, attempt.attempt(), partialFrom, endedAt);
        jdbc.sql("""
                        update metering.usage_minutes
                        set lease_checked = true, lease_attempt = :attempt,
                            lease_alive = (minute_index = :partialIndex and :partialCovered)
                        where session_id = :sessionId and agent_attempt = :attempt and not lease_checked
                          and minute_index >= :partialIndex
                        """)
                .param("attempt", attempt.attempt())
                .param("partialIndex", partialIndex)
                .param("partialCovered", partialCovered)
                .param("sessionId", sessionId)
                .update();
    }

    private long checkedThrough(UUID sessionId, int attempt) {
        jdbc.sql("""
                        insert into metering.metered_attempts (session_id, attempt) values (:sessionId, :attempt)
                        on conflict do nothing
                        """).param("sessionId", sessionId).param("attempt", attempt).update();
        return jdbc.sql("""
                        select checked_through from metering.metered_attempts
                        where session_id = :sessionId and attempt = :attempt
                        """)
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .query(Long.class)
                .single();
    }

    private void lease(UUID sessionId, int index, Instant minuteAt, boolean alive, int attempt) {
        jdbc.sql("""
                        insert into metering.usage_minutes
                            (session_id, minute_index, minute_at, lease_alive, lease_checked, lease_attempt)
                        values (:sessionId, :index, :minuteAt, :alive, true, :attempt)
                        on conflict (session_id, minute_index, minute_at) do update
                        set lease_alive = excluded.lease_alive, lease_checked = true,
                            lease_attempt = excluded.lease_attempt
                        where not usage_minutes.lease_checked
                        """)
                .param("sessionId", sessionId)
                .param("index", index)
                .param("minuteAt", Timestamp.from(minuteAt))
                .param("alive", alive)
                .param("attempt", attempt)
                .update();
    }
}
