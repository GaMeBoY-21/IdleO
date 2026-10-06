package com.idleo.controlplane.metering.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.sessions.MeteredSessions;
import gpu.agent.v1.Session.UsageReport;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Both witnesses for every minute, and only both make a minute billable (ADR 0017). */
@ControlPlaneTest
class WitnessTests {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final UUID HOST = UUID.randomUUID();

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MeterRegistry meters;

    private final FakeMeteredSessions sessions = new FakeMeteredSessions();
    private Witnesses witnesses;
    private UsageRecorder recorder;
    /** minute 0, and the attempt's first RUNNING, a few seconds later. */
    private final Instant startedAt =
            Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofMinutes(10));

    private final Instant runningAt = startedAt;

    @BeforeEach
    void setUp() {
        witnesses = new Witnesses(sessions, jdbc);
        recorder = new UsageRecorder(sessions, jdbc, meters, Clock.systemUTC());
    }

    private UUID session(@Nullable Instant endedAt) {
        var id = UUID.randomUUID();
        sessions.put(
                new MeteredSessions.Session(id, UUID.randomUUID(), 10_000, startedAt, 1, HOST, endedAt == null),
                new MeteredSessions.Attempt(1, HOST, 2, 0, runningAt.minusSeconds(3), runningAt, endedAt));
        jdbcTemplate.update(
                "insert into metering.metered_sessions"
                        + " (session_id, renter_id, started_at, max_spend_paise, next_window_at, created_at)"
                        + " values (?, ?, ?, 10000, ?, now())",
                id,
                UUID.randomUUID(),
                Timestamp.from(startedAt),
                Timestamp.from(startedAt.plus(Duration.ofMinutes(15))));
        return id;
    }

    private void decide(UUID sessionId, Instant now) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> witnesses.decide(sessionId, startedAt, now));
    }

    private void report(UUID sessionId, int attempt, int index, int seconds) {
        recorder.onUsage(
                HOST,
                UsageReport.newBuilder()
                        .setSessionId(sessionId.toString())
                        .setAttempt(attempt)
                        .setMinuteIndex(index)
                        .setGpuSeconds(seconds)
                        .setAvgUtilPct(80)
                        .build());
    }

    /** Each minute's witnesses: index → "agent,lease". */
    private Map<Integer, String> minutes(UUID sessionId) {
        var rows = new java.util.TreeMap<Integer, String>();
        jdbcTemplate.query(
                "select minute_index, agent_reported, lease_alive, lease_checked from metering.usage_minutes"
                        + " where session_id = ? order by minute_index",
                rs -> {
                    rows.put(
                            rs.getInt("minute_index"),
                            (rs.getBoolean("agent_reported") ? "agent" : "-") + ","
                                    + (rs.getBoolean("lease_checked")
                                            ? (rs.getBoolean("lease_alive") ? "lease" : "no-lease")
                                            : "?"));
                },
                sessionId);
        return rows;
    }

    @Test
    void wholeMinutesBothSawAreBillableAndALeaseOnlyMinuteIsNot() {
        var id = session(null);
        sessions.lease(id, 1, runningAt.minusSeconds(3), runningAt.plus(Duration.ofMinutes(5)));
        report(id, 1, 0, 60);
        report(id, 1, 1, 60);

        decide(id, runningAt.plus(Duration.ofSeconds(185)));

        assertThat(minutes(id)).isEqualTo(Map.of(0, "agent,lease", 1, "agent,lease", 2, "-,lease"));
        assertThat(jdbcTemplate
                        .queryForObject(
                                "select minute_at from metering.usage_minutes where session_id = ? and minute_index = 2",
                                Timestamp.class,
                                id)
                        .toInstant())
                .isEqualTo(startedAt.plus(MINUTE.multipliedBy(2)));
    }

    @Test
    void aMinuteTheLeaseDidntWhollyCoverIsNotAliveEvenIfTheAgentReportedIt() {
        var id = session(null);
        // A late renewal: the lease ran out at 90 s and the next stretch began at 100 s.
        sessions.lease(id, 1, runningAt.minusSeconds(3), runningAt.plusSeconds(90));
        sessions.lease(id, 1, runningAt.plusSeconds(100), runningAt.plusSeconds(400));
        for (int index = 0; index < 3; index++) {
            report(id, 1, index, 60);
        }

        decide(id, runningAt.plusSeconds(181));

        assertThat(minutes(id)).isEqualTo(Map.of(0, "agent,lease", 1, "agent,no-lease", 2, "agent,lease"));
    }

    @Test
    void thePartialLastMinuteCountsIfTheLeaseCoveredItUpToTheEnd() {
        var endedAt = runningAt.plusSeconds(150);
        var id = session(endedAt);
        sessions.lease(id, 1, runningAt.minusSeconds(3), endedAt.plusSeconds(1));
        report(id, 1, 0, 60);
        report(id, 1, 1, 60);
        report(id, 1, 2, 30);

        decide(id, endedAt.plusSeconds(5));

        assertThat(minutes(id)).isEqualTo(Map.of(0, "agent,lease", 1, "agent,lease", 2, "agent,lease"));
    }

    @Test
    void aPartialMinuteTheLeaseStoppedCoveringBeforeTheEndIsNotAlive() {
        var endedAt = runningAt.plusSeconds(150);
        var id = session(endedAt);
        sessions.lease(id, 1, runningAt.minusSeconds(3), runningAt.plusSeconds(140));
        report(id, 1, 0, 60);
        report(id, 1, 1, 60);
        report(id, 1, 2, 30);

        decide(id, endedAt.plusSeconds(5));

        assertThat(minutes(id)).containsEntry(2, "agent,no-lease");
    }

    @Test
    void minutesAreDecidedOnceAndOnlyWhenTheyHaveEnded() {
        var id = session(null);
        sessions.lease(id, 1, runningAt.minusSeconds(3), runningAt.plusSeconds(400));

        decide(id, runningAt.plusSeconds(59));
        assertThat(minutes(id)).isEmpty();
        decide(id, runningAt.plusSeconds(60));
        assertThat(minutes(id)).isEqualTo(Map.of(0, "-,lease"));
        sessions.leases.clear();
        decide(id, runningAt.plusSeconds(61));
        assertThat(minutes(id)).isEqualTo(Map.of(0, "-,lease"));
    }

    @Test
    void reportsFromAnotherAttemptOrOutOfRangeAreNeverStored() {
        var id = session(null);

        report(id, 2, 0, 60);
        report(id, 1, 0, 61);
        report(id, 1, 0, 0);
        report(id, 1, 1000, 60);

        assertThat(minutes(id)).isEmpty();
        assertThat(meters.counter("metering.usage.reports", "outcome", "gpu_seconds_out_of_range")
                        .count())
                .isGreaterThanOrEqualTo(2);
        assertThat(meters.counter("metering.usage.reports", "outcome", "index_in_future")
                        .count())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.counter("metering.usage.reports", "outcome", "not_current")
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void aReportBeforeTheAttemptsFirstMinuteIsDropped() {
        var id = UUID.randomUUID();
        sessions.put(
                new MeteredSessions.Session(id, UUID.randomUUID(), 10_000, startedAt, 2, HOST, true),
                new MeteredSessions.Attempt(2, HOST, 2, 5, runningAt.minusSeconds(3), runningAt, null));

        report(id, 2, 4, 60);

        assertThat(minutes(id)).isEmpty();
        assertThat(meters.counter("metering.usage.reports", "outcome", "before_attempt")
                        .count())
                .isGreaterThanOrEqualTo(1);
    }
}
