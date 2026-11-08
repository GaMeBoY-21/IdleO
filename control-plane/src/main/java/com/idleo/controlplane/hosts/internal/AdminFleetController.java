package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.hosts.HostConnections;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fleet (ADR 0056): every host by its agent's version, its distribution's (as its last self-test said), its GPU and
 * driver, its self-tests' pass rate, the abuse blocklist it holds and when it was last seen, on the one list engine, so
 * it filters, saves and exports like every list; its reconnects a day come from the agent gateway's presence history.
 * The summary counts the whole fleet the same ways, and names the hosts behind the release, the stragglers.
 */
@RestController
@ConditionalOnWebApplication
class AdminFleetController {

    /** How far back reconnects are averaged. */
    static final Duration RECONNECT_WINDOW = Duration.ofDays(7);

    private static final String LARGEST_GPU = """
            (select g.%s from hosts.host_gpus g where g.host_id = h.id and g.gone_at is null
             order by g.vram_mb desc, g.gpu_uuid limit 1)""";
    private static final String DISTRO = """
            (select t.distro_version from hosts.self_tests t where t.host_id = h.id order by t.reported_at desc limit 1)""";
    private static final String PASS_RATE = """
            (select round(avg(case when r.passed then 1.0 else 0.0 end), 2) from hosts.self_test_results r
             where r.host_id = h.id and r.reported_at > now() - interval '30 days')""";

    static final AdminListing LIST = AdminListing.of("fleet", AdminPermission.FLEET_READ, false)
            .select("h.id, h.version, h.owner_user_id, h.name, h.status, h.agent_version, h.blocklist_version,"
                    + " h.last_seen_at, " + LARGEST_GPU.formatted("model") + " as gpu_model, "
                    + LARGEST_GPU.formatted("driver_version") + " as driver_version, " + DISTRO + " as distro_version, "
                    + PASS_RATE + " as pass_rate")
            .from("hosts.hosts h")
            .id("h.id")
            .sort("createdAt", AdminListing.Type.UUID, "h.id")
            .sort("agentVersion", AdminListing.Type.TEXT, "coalesce(h.agent_version, '')")
            .sort("lastSeenAt", AdminListing.Type.TEXT, "coalesce(h.last_seen_at::text, '')")
            .sortedBy("createdAt", true)
            .filter("status", "h.status = cast(:status as text)")
            .filter("agentVersion", "h.agent_version = cast(:agentVersion as text)")
            .filter("distroVersion", DISTRO + " = cast(:distroVersion as text)")
            .filter("gpuModel", LARGEST_GPU.formatted("model") + " = cast(:gpuModel as text)")
            .filter("driverVersion", LARGEST_GPU.formatted("driver_version") + " = cast(:driverVersion as text)")
            .filter("blocklistVersion", "coalesce(h.blocklist_version, 'none') = cast(:blocklistVersion as text)")
            .filter("offline", "(cast(:offline as text) = 'day' and h.last_seen_at < now() - interval '24 hours')")
            .filter("tagged", "h.id in (:tagged)")
            .csv("id", "h.id")
            .csv("ownerId", "h.owner_user_id")
            .csv("name", "h.name")
            .csv("status", "h.status")
            .csv("agentVersion", "h.agent_version")
            .csv("distroVersion", DISTRO)
            .csv("gpuModel", LARGEST_GPU.formatted("model"))
            .csv("driverVersion", LARGEST_GPU.formatted("driver_version"))
            .csv("blocklistVersion", "h.blocklist_version")
            .csv("selfTestPassRate", PASS_RATE)
            .csv("lastSeenAt", "h.last_seen_at")
            .build();

    private final JdbcClient jdbc;
    private final AdminTagged tagged;
    private final HostConnections connections;
    private final AgentReleaseFeed feed;
    private final Clock clock;

    AdminFleetController(
            JdbcClient jdbc, AdminTagged tagged, HostConnections connections, AgentReleaseFeed feed, Clock clock) {
        this.jdbc = jdbc;
        this.tagged = tagged;
        this.connections = connections;
        this.feed = feed;
        this.clock = clock;
    }

    /**
     * One host in the fleet.
     *
     * @param selfTestPassRate the share of its self-tests that passed over the last 30 days; null with none
     * @param reconnectsPerDay its reconnects a day, averaged over the last 7 days
     * @param straggler its agent is older than the release the feed holds
     */
    record AdminFleetHost(
            UUID id,
            UUID ownerId,
            String name,
            String status,
            @Nullable String agentVersion,
            @Nullable String distroVersion,
            @Nullable String gpuModel,
            @Nullable String driverVersion,
            @Nullable Double selfTestPassRate,
            @Nullable String blocklistVersion,
            @Nullable Instant lastSeenAt,
            double reconnectsPerDay,
            boolean straggler,
            long version) {}

    record AdminFleetPage(
            List<AdminFleetHost> hosts, @Nullable String nextCursor) {}

    /** How many hosts have each value. */
    record AdminFleetCount(String value, long hosts) {}

    /**
     * The fleet at a glance.
     *
     * @param latestRelease the release the feed holds; null before there's one
     * @param stragglers hosts whose agent is older than it
     * @param offlineDay hosts not seen for more than a day
     * @param selfTestPassRate the share of all self-tests that passed over the last 30 days; null with none
     */
    record AdminFleetSummary(
            long hosts,
            @Nullable String latestRelease,
            long stragglers,
            long offlineDay,
            @Nullable Double selfTestPassRate,
            List<AdminFleetCount> agentVersions,
            List<AdminFleetCount> distroVersions,
            List<AdminFleetCount> gpuModels,
            List<AdminFleetCount> driverVersions,
            List<AdminFleetCount> blocklistVersions) {}

    /**
     * {@code agentVersion}, {@code distroVersion}, {@code gpuModel}, {@code driverVersion} and {@code blocklistVersion}
     * ({@code none} for none) are exact; {@code offline=day} is hosts not seen for more than a day.
     */
    @RequiresPermission(AdminPermission.FLEET_READ)
    @AdminList("fleet")
    @GetMapping("/admin/v1/fleet")
    AdminFleetPage fleet(
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String agentVersion,
            @RequestParam(required = false) @Nullable String distroVersion,
            @RequestParam(required = false) @Nullable String gpuModel,
            @RequestParam(required = false) @Nullable String driverVersion,
            @RequestParam(required = false) @Nullable String blocklistVersion,
            @RequestParam(required = false) @Nullable String offline,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        record Row(
                UUID id,
                UUID ownerId,
                String name,
                String status,
                @Nullable String agentVersion,
                @Nullable String distroVersion,
                @Nullable String gpuModel,
                @Nullable String driverVersion,
                @Nullable Double passRate,
                @Nullable String blocklistVersion,
                @Nullable Instant lastSeenAt,
                long version) {}
        var asked = AdminListing.Asked.of(
                sort,
                "status",
                status,
                "agentVersion",
                agentVersion,
                "distroVersion",
                distroVersion,
                "gpuModel",
                gpuModel,
                "driverVersion",
                driverVersion,
                "blocklistVersion",
                blocklistVersion,
                "offline",
                offline,
                "tagged",
                tagged.subjects("host", tag));
        var page = LIST.page(jdbc, asked, cursor, limit, (rs, n) -> {
            var seen = rs.getTimestamp("last_seen_at");
            var rate = rs.getBigDecimal("pass_rate");
            return new Row(
                    rs.getObject("id", UUID.class),
                    rs.getObject("owner_user_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("status"),
                    rs.getString("agent_version"),
                    rs.getString("distro_version"),
                    rs.getString("gpu_model"),
                    rs.getString("driver_version"),
                    rate == null ? null : rate.doubleValue(),
                    rs.getString("blocklist_version"),
                    seen == null ? null : seen.toInstant(),
                    rs.getLong("version"));
        });
        var opened = connections.streamsOpened(
                page.rows().stream().map(Row::id).toList(), clock.instant().minus(RECONNECT_WINDOW));
        var latest = feed.latest().orElse(null);
        var hosts = new ArrayList<AdminFleetHost>();
        for (var row : page.rows()) {
            double reconnects = Math.max(0, opened.getOrDefault(row.id(), 0L) - 1) / (double) RECONNECT_WINDOW.toDays();
            hosts.add(new AdminFleetHost(
                    row.id(),
                    row.ownerId(),
                    row.name(),
                    row.status(),
                    row.agentVersion(),
                    row.distroVersion(),
                    row.gpuModel(),
                    row.driverVersion(),
                    row.passRate(),
                    row.blocklistVersion(),
                    row.lastSeenAt(),
                    com.idleo.controlplane.ControlRoom.round(reconnects),
                    latest != null && older(row.agentVersion(), latest.semver()),
                    row.version()));
        }
        return new AdminFleetPage(hosts, page.nextCursor());
    }

    @RequiresPermission(AdminPermission.FLEET_READ)
    @GetMapping("/admin/v1/fleet/summary")
    AdminFleetSummary summary() {
        var latest = feed.latest().orElse(null);
        var versions = counts("h.agent_version");
        long stragglers = latest == null
                ? 0
                : versions.stream()
                        .filter(count -> older(count.value(), latest.semver()))
                        .mapToLong(AdminFleetCount::hosts)
                        .sum();
        var rate = jdbc.sql("""
                        select round(avg(case when passed then 1.0 else 0.0 end), 2) from hosts.self_test_results
                        where reported_at > :since
                        """)
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(30))))
                .query(java.math.BigDecimal.class)
                .optional()
                .map(java.math.BigDecimal::doubleValue)
                .orElse(null);
        return new AdminFleetSummary(
                jdbc.sql("select count(*) from hosts.hosts where status <> 'removed'")
                        .query(Long.class)
                        .single(),
                latest == null ? null : latest.version(),
                stragglers,
                jdbc.sql("""
                                select count(*) from hosts.hosts
                                where status <> 'removed' and last_seen_at < :day
                                """)
                        .param("day", Timestamp.from(clock.instant().minus(Duration.ofDays(1))))
                        .query(Long.class)
                        .single(),
                rate,
                versions,
                counts(DISTRO),
                counts(LARGEST_GPU.formatted("model")),
                counts(LARGEST_GPU.formatted("driver_version")),
                counts("h.blocklist_version"));
    }

    private List<AdminFleetCount> counts(String value) {
        return jdbc.sql("select coalesce(" + value + ", 'none') as value, count(*) as hosts from hosts.hosts h"
                        + " where h.status <> 'removed' group by 1 order by 2 desc, 1 limit 20")
                .query((rs, n) -> new AdminFleetCount(rs.getString("value"), rs.getLong("hosts")))
                .list();
    }

    private static boolean older(@Nullable String version, com.idleo.controlplane.SemanticVersion than) {
        return version != null
                && com.idleo.controlplane.SemanticVersion.parse(version)
                        .map(parsed -> parsed.compareTo(than) < 0)
                        .orElse(false);
    }
}
