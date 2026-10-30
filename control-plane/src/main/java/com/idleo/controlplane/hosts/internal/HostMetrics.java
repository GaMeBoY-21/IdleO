package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AfterCommit;
import com.idleo.controlplane.hosts.HostAgentReports;
import com.idleo.controlplane.hosts.HostPresence;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The hosts module's meters (ADR 0036): the facts this module owns about hosts and what their agents report.
 *
 * <ul>
 *   <li>{@code hosts}: hosts now, by {@code state} (pending, verified, active, paused, suspended) and {@code online},
 *       worked out once a minute. Every instance reports the same figures, so a dashboard takes the max, not the sum.
 *   <li>{@code hosts.links}: host links {@code started}, {@code approved} and {@code denied} (ADR 0032).
 *   <li>{@code agents.self-tests}: self-tests reported, by {@code outcome}; {@code agents.self-test.failures}: the
 *       checks that failed, by {@code check}.
 *   <li>{@code agents.updates}: update results as they're reported, by {@code version} and {@code outcome};
 *       {@code agents.update.results}: every host's latest result for each version, from the database, which the
 *       releases alert reads (rolled back over 10% of the results for a version).
 * </ul>
 */
@Component
class HostMetrics {

    /** A check's name or a version is a short code; anything else is counted as {@code other}. */
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,39}");

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final HostPresence presence;
    private final Clock clock;
    private final MultiGauge hosts;
    private final MultiGauge updateResults;

    HostMetrics(MeterRegistry registry, JdbcClient jdbc, HostPresence presence, Clock clock) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.presence = presence;
        this.clock = clock;
        this.hosts = MultiGauge.builder("hosts")
                .description("Hosts now, by state and whether their agent is connected")
                .register(registry);
        this.updateResults = MultiGauge.builder("agents.update.results")
                .description("Each host's latest result for each agent version, by version and outcome")
                .register(registry);
    }

    private record Host(UUID id, String state) {}

    /** Once a minute, when scheduling runs (never for an operator command). */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT5S")
    void refresh() {
        var all = jdbc.sql("""
                        select id,
                               case when status = 'active' and paused_at is not null
                                         and (paused_until is null or paused_until > :now)
                                    then 'paused' else status end as state
                        from hosts.hosts where status <> 'removed'
                        """)
                .param("now", Timestamp.from(Instant.now(clock)))
                .query((rs, n) -> new Host(rs.getObject("id", UUID.class), rs.getString("state")))
                .list();
        var seen = presence.seen(all.stream().map(Host::id).toList());
        if (!seen.known()) {
            // Presence couldn't be read: the counts are unknown, a gap in the series, never a fleet gone offline.
            hosts.register(List.of(), true);
        } else {
            registerCounts(all, seen.connections().keySet());
        }

        var results = jdbc.sql("select version, outcome, count(*) as hosts from hosts.update_results group by 1, 2")
                .query((rs, n) -> MultiGauge.Row.of(
                        Tags.of("version", code(rs.getString("version")), "outcome", rs.getString("outcome")),
                        rs.getLong("hosts")))
                .list();
        updateResults.register(new ArrayList<>(results), true);
    }

    /** Hosts by state and by whether their agent is connected, every pair counted, zero included. */
    private void registerCounts(List<Host> all, Set<UUID> online) {
        var counts = new HashMap<Tags, Long>();
        for (var state : List.of("pending", "verified", "active", "paused", "suspended")) {
            counts.put(Tags.of("state", state, "online", "true"), 0L);
            counts.put(Tags.of("state", state, "online", "false"), 0L);
        }
        for (var host : all) {
            counts.merge(
                    Tags.of("state", host.state(), "online", String.valueOf(online.contains(host.id()))),
                    1L,
                    Long::sum);
        }
        var rows = new ArrayList<MultiGauge.Row<?>>();
        counts.forEach((tags, count) -> rows.add(MultiGauge.Row.of(tags, count)));
        hosts.register(rows, true);
    }

    void linkStarted() {
        AfterCommit.run(
                () -> registry.counter("hosts.links", "outcome", "started").increment());
    }

    void linkApproved() {
        AfterCommit.run(
                () -> registry.counter("hosts.links", "outcome", "approved").increment());
    }

    void linkDenied() {
        AfterCommit.run(
                () -> registry.counter("hosts.links", "outcome", "denied").increment());
    }

    void selfTest(HostAgentReports.SelfTest selfTest) {
        AfterCommit.run(() -> {
            registry.counter("agents.self-tests", "outcome", selfTest.passed() ? "passed" : "failed")
                    .increment();
            for (var check : selfTest.checks()) {
                if (!check.passed()) {
                    registry.counter("agents.self-test.failures", "check", code(check.name()))
                            .increment();
                }
            }
        });
    }

    void updateResult(String version, HostAgentReports.UpdateOutcome outcome) {
        AfterCommit.run(() -> registry.counter(
                        "agents.updates",
                        "version",
                        code(version),
                        "outcome",
                        outcome.name().toLowerCase(Locale.ROOT))
                .increment());
    }

    private static String code(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        return CODE.matcher(lower).matches() ? lower : "other";
    }
}
