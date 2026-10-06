package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.agentgateway.UsageReports;
import com.idleo.controlplane.sessions.MeteredSessions;
import gpu.agent.v1.Session.UsageReport;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The first witness (ADR 0017): an agent's report of one minute sets {@code agent_reported}. Only the session's
 * current attempt on its current host counts; anything else is acked and ignored (ADR 0007). A report with a
 * value out of range is acked and dropped with a counted reason, never stored.
 */
@Component
class UsageRecorder implements UsageReports {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private final MeteredSessions sessions;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final Clock clock;

    UsageRecorder(MeteredSessions sessions, JdbcClient jdbc, MeterRegistry meters, Clock clock) {
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public void onUsage(UUID hostId, UsageReport report) {
        var sessionId = UUID.fromString(report.getSessionId());
        var session = sessions.session(sessionId).orElse(null);
        if (session == null || session.attempt() != report.getAttempt() || !hostId.equals(session.hostId())) {
            outcome("not_current");
            log.info(
                    "Ignored host {}'s usage for session {} attempt {}: not the session's current attempt there",
                    hostId,
                    sessionId,
                    report.getAttempt());
            return;
        }
        var startedAt = session.startedAt();
        var attempt = sessions.attempts(sessionId).stream()
                .filter(candidate -> candidate.attempt() == report.getAttempt())
                .findFirst()
                .orElse(null);
        if (startedAt == null || attempt == null || attempt.startDeliveredAt() == null) {
            dropped("not_started", sessionId, report);
            return;
        }
        int index = report.getMinuteIndex();
        var since = attempt.runningAt() == null ? attempt.startDeliveredAt() : attempt.runningAt();
        long possible = attempt.minuteIndexBase()
                + Duration.between(since, Instant.now(clock)).toMinutes()
                + 1;
        if (index < 0) {
            dropped("negative_index", sessionId, report);
        } else if (index < attempt.minuteIndexBase()) {
            dropped("before_attempt", sessionId, report);
        } else if (index > possible) {
            dropped("index_in_future", sessionId, report);
        } else if (report.getGpuSeconds() < 1 || report.getGpuSeconds() > 60) {
            dropped("gpu_seconds_out_of_range", sessionId, report);
        } else if (report.getAvgUtilPct() < 0 || report.getAvgUtilPct() > 100) {
            dropped("utilisation_out_of_range", sessionId, report);
        } else {
            record(sessionId, startedAt.plus(Duration.ofMinutes(index)), report);
            outcome("recorded");
        }
    }

    private void record(UUID sessionId, Instant minuteAt, UsageReport report) {
        jdbc.sql("""
                        insert into metering.usage_minutes
                            (session_id, minute_index, minute_at, agent_reported, agent_attempt, gpu_seconds, avg_util_pct)
                        values (:sessionId, :index, :minuteAt, true, :attempt, :seconds, :util)
                        on conflict (session_id, minute_index, minute_at) do update
                        set agent_reported = true, agent_attempt = excluded.agent_attempt,
                            gpu_seconds = excluded.gpu_seconds, avg_util_pct = excluded.avg_util_pct
                        where not usage_minutes.agent_reported
                        """)
                .param("sessionId", sessionId)
                .param("index", report.getMinuteIndex())
                .param("minuteAt", Timestamp.from(minuteAt))
                .param("attempt", report.getAttempt())
                .param("seconds", report.getGpuSeconds())
                .param("util", report.getAvgUtilPct())
                .update();
    }

    private void dropped(String reason, UUID sessionId, UsageReport report) {
        outcome(reason);
        log.warn(
                "Dropped usage for session {} attempt {} minute {}: {}",
                sessionId,
                report.getAttempt(),
                report.getMinuteIndex(),
                reason);
    }

    private void outcome(String outcome) {
        meters.counter("metering.usage.reports", "outcome", outcome).increment();
    }
}
