package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.HostScores;
import com.idleo.controlplane.hosts.HostSessionResults;
import com.idleo.controlplane.hosts.HostTrustSignals;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The host score: reliability from the last 30 days of {@link
 * HostReliability}, and bandwidth from the speed check against the fleet's 90th percentile, stored in
 * {@code hosts.host_scores} and read by the scheduler through {@code SchedulableHosts}. Worked out for every host
 * nightly, and for one host when sessions says one of its sessions ended or was lost. Cache isn't here: it is a fact
 * of the moment, from the agent's heartbeat.
 */
@Service
class HostScoreService implements HostScores {

    private static final Logger log = LoggerFactory.getLogger(HostScoreService.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final HostSessionResults sessions;
    private final HostTrustSignals trust;

    HostScoreService(JdbcClient jdbc, Clock clock, HostSessionResults sessions, HostTrustSignals trust) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.sessions = sessions;
        this.trust = trust;
    }

    /** A host's stored score, with what it was made of and when it was worked out. */
    record Stored(
            HostReliability.Breakdown breakdown,
            double bandwidth,
            @Nullable Integer speedKbps,
            Instant computedAt) {}

    /** The stored scores of these hosts; a host not worked out yet isn't in the map. */
    Map<UUID, Stored> stored(Collection<UUID> hostIds) {
        var scores = new HashMap<UUID, Stored>();
        if (hostIds.isEmpty()) {
            return scores;
        }
        jdbc.sql("select * from hosts.host_scores where host_id in (:hostIds)")
                .param("hostIds", hostIds)
                .query(rs -> {
                    scores.put(
                            rs.getObject("host_id", UUID.class),
                            new Stored(
                                    new HostReliability.Breakdown(
                                            rs.getDouble("reliability"),
                                            rs.getDouble("session_score"),
                                            rs.getDouble("self_test_score"),
                                            rs.getDouble("penalty"),
                                            rs.getInt("sessions_finished"),
                                            rs.getInt("sessions_lost"),
                                            rs.getInt("self_tests_passed"),
                                            rs.getInt("self_tests_failed"),
                                            rs.getInt("reports_upheld"),
                                            rs.getInt("flags")),
                                    rs.getDouble("bandwidth"),
                                    rs.getObject("speed_kbps", Integer.class),
                                    rs.getTimestamp("computed_at").toInstant()));
                });
        return scores;
    }

    /** What a host with no data counts as: average on both. */
    static final double NEUTRAL_BANDWIDTH = 0.5;

    @Scheduled(cron = "${hosts.score.refresh-cron}", zone = "${hosts.score.zone}")
    void nightly() {
        try {
            refreshAll();
        } catch (RuntimeException e) {
            log.warn("The nightly host scores failed; they run again tomorrow, and after each session", e);
        }
    }

    @Override
    public void refresh(UUID hostId) {
        refresh(List.of(hostId));
    }

    /** Every host that isn't removed. */
    void refreshAll() {
        refresh(jdbc.sql("select id from hosts.hosts where status <> 'removed'")
                .query(UUID.class)
                .list());
    }

    private void refresh(Collection<UUID> hostIds) {
        if (hostIds.isEmpty()) {
            return;
        }
        var now = Instant.now(clock);
        var since = now.minus(HostReliability.WINDOW);
        var sessionEvents = new HashMap<UUID, List<HostReliability.Event>>();
        for (var result : sessions.since(hostIds, since)) {
            sessionEvents
                    .computeIfAbsent(result.hostId(), id -> new java.util.ArrayList<>())
                    .add(new HostReliability.Event(result.at(), result.finished()));
        }
        var selfTests = selfTests(hostIds, since);
        var reports = new HashMap<UUID, List<Instant>>();
        var flags = new HashMap<UUID, List<Instant>>();
        for (var signal : trust.since(hostIds, since)) {
            var bucket = signal.kind() == HostTrustSignals.Kind.FLAG ? flags : reports;
            bucket.computeIfAbsent(signal.hostId(), id -> new java.util.ArrayList<>())
                    .add(signal.at());
        }
        var speeds = speeds(hostIds);
        double fleetP90 = fleetSpeedP90();
        for (var hostId : hostIds) {
            var breakdown = HostReliability.of(
                    now,
                    sessionEvents.getOrDefault(hostId, List.of()),
                    selfTests.getOrDefault(hostId, List.of()),
                    reports.getOrDefault(hostId, List.of()),
                    flags.getOrDefault(hostId, List.of()));
            var speed = speeds.get(hostId);
            store(hostId, breakdown, speed, bandwidth(speed, fleetP90), now);
        }
    }

    /** 0 to 1: the host's speed against the fleet's 90th percentile; average for a host not measured yet. */
    static double bandwidth(@Nullable Integer speedKbps, double fleetP90Kbps) {
        if (speedKbps == null || fleetP90Kbps <= 0) {
            return NEUTRAL_BANDWIDTH;
        }
        return Math.min(1.0, speedKbps / fleetP90Kbps);
    }

    private Map<UUID, List<HostReliability.Event>> selfTests(Collection<UUID> hostIds, Instant since) {
        var results = new HashMap<UUID, List<HostReliability.Event>>();
        jdbc.sql("""
                        select host_id, passed, reported_at from hosts.self_test_results
                        where host_id in (:hostIds) and reported_at >= :since
                        """)
                .param("hostIds", hostIds)
                .param("since", Timestamp.from(since))
                .query(rs -> {
                    results.computeIfAbsent(rs.getObject("host_id", UUID.class), id -> new java.util.ArrayList<>())
                            .add(new HostReliability.Event(
                                    rs.getTimestamp("reported_at").toInstant(), rs.getBoolean("passed")));
                });
        return results;
    }

    /** The mean of the last upload and download, in kbit/s (the one that was measured when only one was). */
    private Map<UUID, Integer> speeds(Collection<UUID> hostIds) {
        var speeds = new HashMap<UUID, Integer>();
        jdbc.sql("""
                        select host_id, (coalesce(upload_kbps, download_kbps) + coalesce(download_kbps, upload_kbps)) / 2
                                   as speed
                        from hosts.self_tests
                        where host_id in (:hostIds) and (upload_kbps is not null or download_kbps is not null)
                        """).param("hostIds", hostIds).query(rs -> {
            speeds.put(rs.getObject("host_id", UUID.class), rs.getInt("speed"));
        });
        return speeds;
    }

    private double fleetSpeedP90() {
        var p90 = jdbc.sql("""
                        select percentile_cont(0.9) within group (order by
                                   (coalesce(t.upload_kbps, t.download_kbps) + coalesce(t.download_kbps, t.upload_kbps)) / 2.0)
                        from hosts.self_tests t join hosts.hosts h on h.id = t.host_id
                        where h.status <> 'removed' and (t.upload_kbps is not null or t.download_kbps is not null)
                        """).query(Double.class).optional();
        return p90.orElse(0.0);
    }

    private void store(
            UUID hostId, HostReliability.Breakdown b, @Nullable Integer speed, double bandwidth, Instant now) {
        jdbc.sql("""
                        insert into hosts.host_scores
                            (host_id, reliability, bandwidth, session_score, self_test_score, penalty,
                             sessions_finished, sessions_lost, self_tests_passed, self_tests_failed, reports_upheld,
                             flags, speed_kbps, computed_at)
                        values (:hostId, :reliability, :bandwidth, :sessionScore, :selfTestScore, :penalty,
                                :finished, :lost, :passed, :failed, :reports, :flags, :speed, :now)
                        on conflict (host_id) do update
                        set reliability = excluded.reliability, bandwidth = excluded.bandwidth,
                            session_score = excluded.session_score, self_test_score = excluded.self_test_score,
                            penalty = excluded.penalty, sessions_finished = excluded.sessions_finished,
                            sessions_lost = excluded.sessions_lost, self_tests_passed = excluded.self_tests_passed,
                            self_tests_failed = excluded.self_tests_failed, reports_upheld = excluded.reports_upheld,
                            flags = excluded.flags, speed_kbps = excluded.speed_kbps,
                            computed_at = excluded.computed_at
                        """)
                .param("hostId", hostId)
                .param("reliability", b.reliability())
                .param("bandwidth", bandwidth)
                .param("sessionScore", b.sessionScore())
                .param("selfTestScore", b.selfTestScore())
                .param("penalty", b.penalty())
                .param("finished", b.sessionsFinished())
                .param("lost", b.sessionsLost())
                .param("passed", b.selfTestsPassed())
                .param("failed", b.selfTestsFailed())
                .param("reports", b.reportsUpheld())
                .param("flags", b.flags())
                .param("speed", speed)
                .param("now", Timestamp.from(now))
                .update();
    }
}
