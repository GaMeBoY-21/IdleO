package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.OneAtATime;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.AgentReleaseCached;
import com.idleo.controlplane.hosts.HostConnections;
import com.idleo.controlplane.hosts.HostSessionResults;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.AdminNoticed;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staged rollouts of the Windows app's signed releases (ADR 0056, ADR 0034): which hosts are offered a release, never
 * how the agent checks it. A newly cached release starts at 5% of hosts, chosen by host id ({@link
 * ReleaseManifest#bucket}, so widening only ever adds hosts), and never more than the signed manifest's own percentage.
 * Pausing is immediate; widening (5, 25, 50, 100) is an approved request. Every few minutes the release's hosts are
 * compared with the rest, and a rollout whose hosts lose noticeably more sessions, or reconnect far more, pauses itself.
 * Every step is audited.
 */
@Service
@EnableConfigurationProperties(RolloutProperties.class)
class AgentRollouts {

    private static final Logger log = LoggerFactory.getLogger(AgentRollouts.class);

    static final List<Integer> STEPS = List.of(5, 25, 50, 100);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;
    private final AdminAccess admins;
    private final ApplicationEventPublisher events;
    private final HostSessionResults results;
    private final HostConnections connections;
    private final OneAtATime oneAtATime;
    private final IdGenerator ids;
    private final Clock clock;
    private final RolloutProperties properties;

    AgentRollouts(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            AuditTrail audit,
            AdminAccess admins,
            ApplicationEventPublisher events,
            HostSessionResults results,
            HostConnections connections,
            OneAtATime oneAtATime,
            IdGenerator ids,
            Clock clock,
            RolloutProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.audit = audit;
        this.admins = admins;
        this.events = events;
        this.results = results;
        this.connections = connections;
        this.oneAtATime = oneAtATime;
        this.ids = ids;
        this.clock = clock;
        this.properties = properties;
    }

    /** One release's rollout. */
    record Rollout(
            String release,
            int percent,
            boolean paused,
            String reason,
            @Nullable UUID changedBy,
            Instant changedAt,
            Instant createdAt,
            long version) {}

    /** How its hosts are doing against the rest, over the window. */
    record Health(
            int hosts,
            long sessions,
            long lost,
            double reconnectsPerHostDay,
            int restHosts,
            long restSessions,
            long restLost,
            double restReconnectsPerHostDay) {

        double failureRate() {
            return sessions == 0 ? 0 : (double) lost / sessions;
        }

        double restFailureRate() {
            return restSessions == 0 ? 0 : (double) restLost / restSessions;
        }
    }

    Optional<Rollout> find(String release) {
        return jdbc.sql("select * from hosts.agent_rollouts where release = :release")
                .param("release", release)
                .query(AgentRollouts::row)
                .optional();
    }

    List<Rollout> all() {
        return jdbc.sql("select * from hosts.agent_rollouts order by created_at desc limit 20")
                .query(AgentRollouts::row)
                .list();
    }

    /** The share of hosts offered {@code release} now: none while paused, or before it has a rollout. */
    int offered(String release, int signedPercent) {
        return find(release)
                .filter(rollout -> !rollout.paused())
                .map(rollout -> Math.min(rollout.percent(), signedPercent))
                .orElse(0);
    }

    /** A release was cached: it starts at 5%, once. */
    void started(String release) {
        transactions.executeWithoutResult(status -> {
            var now = Timestamp.from(clock.instant());
            int added =
                    jdbc.sql("""
                            insert into hosts.agent_rollouts (release, percent, reason, changed_at, created_at)
                            values (:release, 5, 'a new release starts with 5% of hosts', :now, :now)
                            on conflict (release) do nothing
                            """).param("release", release).param("now", now).update();
            if (added == 1) {
                audit.record(new AuditTrail.Entry(
                        AuditTrail.Actor.system("release feed"),
                        "rollout.start",
                        "rollout",
                        release,
                        "a new release starts with 5% of hosts",
                        null,
                        Map.of("percent", 5)));
            }
        });
    }

    /** Pauses it now: no host is offered it until it's widened again. */
    Rollout pause(String release, String reason, AuditTrail.Actor actor) {
        transactions.executeWithoutResult(status -> {
            var before = lock(release);
            if (before.paused()) {
                throw ApiProblem.of(Problem.ACTION_REFUSED, "The rollout of " + release + " is paused already.");
            }
            jdbc.sql("""
                            update hosts.agent_rollouts set paused = true, reason = :reason, changed_by = :by,
                                changed_at = :now
                            where release = :release
                            """)
                    .param("reason", reason)
                    .param("by", actor.userId())
                    .param("now", Timestamp.from(clock.instant()))
                    .param("release", release)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    "rollout.pause",
                    "rollout",
                    release,
                    reason,
                    Map.of("percent", before.percent(), "paused", false),
                    Map.of("percent", before.percent(), "paused", true)));
            if (actor.userId() == null) {
                tellTheOwners(release, reason);
            }
        });
        return find(release).orElseThrow();
    }

    /** Widens it to {@code percent} and runs it again if it was paused: once approved. */
    void widen(String release, int percent, String reason, AuditTrail.Actor actor) {
        if (!STEPS.contains(percent)) {
            throw ApiProblem.invalid("percent", "is 5, 25, 50 or 100.");
        }
        transactions.executeWithoutResult(status -> {
            var before = lock(release);
            if (percent < before.percent()) {
                throw ApiProblem.of(
                        Problem.ACTION_REFUSED,
                        "A rollout only widens: pause it to stop offering it, rather than narrowing it.");
            }
            if (percent == before.percent() && !before.paused()) {
                throw ApiProblem.of(
                        Problem.ACTION_REFUSED, "The rollout of " + release + " is at " + percent + "% already.");
            }
            jdbc.sql("""
                            update hosts.agent_rollouts set percent = :percent, paused = false, reason = :reason,
                                changed_by = :by, changed_at = :now
                            where release = :release
                            """)
                    .param("percent", percent)
                    .param("reason", reason)
                    .param("by", actor.userId())
                    .param("now", Timestamp.from(clock.instant()))
                    .param("release", release)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    "rollout.widen",
                    "rollout",
                    release,
                    reason,
                    Map.of("percent", before.percent(), "paused", before.paused()),
                    Map.of("percent", percent, "paused", false)));
        });
        // The agents this instance holds are offered it now; every instance's feed notices the change at its refresh.
        events.publishEvent(new AgentReleaseCached(release));
    }

    /** How the release's hosts are doing against the rest, over the window. */
    Health health(String release) {
        var since = clock.instant().minus(properties.window());
        var cohort = jdbc.sql("""
                        select id from hosts.hosts where agent_version = :release and status <> 'removed'
                        """).param("release", release).query(UUID.class).list();
        var rest = jdbc.sql("""
                        select id from hosts.hosts
                        where status = 'active' and agent_version is not null and agent_version <> :release
                        """).param("release", release).query(UUID.class).list();
        var cohortResults = cohort.isEmpty() ? List.<HostSessionResults.Result>of() : results.since(cohort, since);
        var restResults = rest.isEmpty() ? List.<HostSessionResults.Result>of() : results.since(rest, since);
        double days = properties.window().toMinutes() / (24.0 * 60);
        return new Health(
                cohort.size(),
                cohortResults.size(),
                cohortResults.stream().filter(result -> !result.finished()).count(),
                reconnects(cohort, since, days),
                rest.size(),
                restResults.size(),
                restResults.stream().filter(result -> !result.finished()).count(),
                reconnects(rest, since, days));
    }

    /** Reconnects a host a day: each stream after a host's first in the window. */
    private double reconnects(List<UUID> hosts, Instant since, double days) {
        if (hosts.isEmpty()) {
            return 0;
        }
        long again = connections.streamsOpened(hosts, since).values().stream()
                .mapToLong(opened -> Math.max(0, opened - 1))
                .sum();
        return Math.round(100.0 * again / hosts.size() / days) / 100.0;
    }

    /** Why the rollout should pause by itself, or null when its hosts do no worse than the rest. */
    @Nullable
    String worse(Health health) {
        if (health.sessions() >= properties.minSessions()
                && health.failureRate() - health.restFailureRate() > properties.failureMargin()) {
            return "its hosts lost %d of %d sessions (%.0f%%) against %.0f%% on the rest"
                    .formatted(
                            health.lost(),
                            health.sessions(),
                            100 * health.failureRate(),
                            100 * health.restFailureRate());
        }
        if (health.hosts() > 0
                && health.reconnectsPerHostDay() >= properties.minReconnects()
                && health.reconnectsPerHostDay() > properties.reconnectFactor() * health.restReconnectsPerHostDay()) {
            return "its hosts reconnected %.1f times a day each against %.1f on the rest"
                    .formatted(health.reconnectsPerHostDay(), health.restReconnectsPerHostDay());
        }
        return null;
    }

    /** Pauses every rolling release whose hosts do noticeably worse than the rest; on one instance at a time. */
    @Scheduled(
            fixedDelayString = "${hosts.rollouts.check-every:5m}",
            initialDelayString = "${hosts.rollouts.check-every:5m}")
    void check() {
        try {
            oneAtATime.run("hosts:rollouts", this::checkNow);
        } catch (RuntimeException e) {
            log.warn("Checking the rollouts failed; it runs again shortly", e);
        }
    }

    void checkNow() {
        for (var rollout : all()) {
            if (rollout.paused() || rollout.percent() == 100) {
                continue;
            }
            var why = worse(health(rollout.release()));
            if (why != null) {
                log.warn("Pausing the rollout of {}: {}", rollout.release(), why);
                pause(rollout.release(), "paused by itself: " + why, AuditTrail.Actor.system("rollout check"));
            }
        }
    }

    private void tellTheOwners(String release, String reason) {
        var id = ids.newId();
        for (var owner : admins.owners()) {
            events.publishEvent(new AdminNoticed(
                    owner,
                    "rollout:" + release + ":" + id + ":" + owner,
                    "The rollout of " + release + " paused",
                    "The rollout of " + release + " " + reason + ". Sign in to the console's Fleet to look.",
                    "/admin/fleet/"));
        }
    }

    private Rollout lock(String release) {
        return jdbc.sql("select * from hosts.agent_rollouts where release = :release for update")
                .param("release", release)
                .query(AgentRollouts::row)
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no rollout of that release."));
    }

    private static Rollout row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Rollout(
                rs.getString("release"),
                rs.getInt("percent"),
                rs.getBoolean("paused"),
                rs.getString("reason"),
                rs.getObject("changed_by", UUID.class),
                rs.getTimestamp("changed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version"));
    }
}
