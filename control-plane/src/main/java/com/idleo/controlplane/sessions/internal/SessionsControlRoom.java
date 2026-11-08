package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ControlRoom;
import com.idleo.controlplane.hosts.HostGpus;
import com.idleo.controlplane.hosts.HostOwners;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The sessions module's parts of the live dashboard (ADR 0056): the marketplace now and over the last 24 hours, the
 * demand heatmap, and the renters' and hosts' first sessions for the funnels, all from its own tables.
 */
@Component
class SessionsControlRoom implements ControlRoom.MarketplaceSource, ControlRoom.FunnelSource {

    /** What a session ended or stopped short with, counted as a failure. */
    static final List<String> FAILED_STATES = List.of("FAILED", "NO_HOST_FREE", "HOST_LOST");

    private final JdbcClient jdbc;
    private final HostGpus gpus;
    private final HostOwners owners;

    SessionsControlRoom(JdbcClient jdbc, HostGpus gpus, HostOwners owners) {
        this.jdbc = jdbc;
        this.gpus = gpus;
        this.owners = owners;
    }

    private record Ran(UUID hostId, String gpuUuid, int gpus, Instant from, Instant to) {}

    @Override
    public ControlRoom.Marketplace marketplace(Instant now) {
        var day = Timestamp.from(now.minus(Duration.ofHours(24)));
        long running = count("select count(*) from sessions.sessions where state in ('RUNNING', 'FINISHING')");
        long waiting = count("select count(*) from sessions.sessions where state = 'REQUESTED'");
        var midnight = java.time.LocalDate.ofInstant(now, ControlRoom.INDIA)
                .atStartOfDay(ControlRoom.INDIA)
                .toInstant();
        long today = jdbc.sql("select count(*) from sessions.sessions where created_at >= :midnight")
                .param("midnight", Timestamp.from(midnight))
                .query(Long.class)
                .single();
        var match = jdbc.sql("""
                        with first_match as (
                            select s.id, min(e.at) - s.created_at as waited
                            from sessions.sessions s
                            join sessions.session_events e on e.session_id = s.id and e.to_state = 'MATCHED'
                            where s.created_at >= :day
                            group by s.id, s.created_at
                        )
                        select percentile_cont(0.5) within group (order by extract(epoch from waited)) as p50,
                               percentile_cont(0.95) within group (order by extract(epoch from waited)) as p95
                        from first_match
                        """)
                .param("day", day)
                .query((rs, n) -> new Double[] {
                    rs.getObject("p50") == null ? null : ControlRoom.round(rs.getDouble("p50")),
                    rs.getObject("p95") == null ? null : ControlRoom.round(rs.getDouble("p95"))
                })
                .single();
        var starts = jdbc.sql("""
                        select count(*) as attempted, count(running_at) as succeeded
                        from sessions.attempts where created_at >= :day
                        """)
                .param("day", day)
                .query((rs, n) -> new long[] {rs.getLong("attempted"), rs.getLong("succeeded")})
                .single();
        var failures = jdbc.sql("""
                        select reason, count(*) as n from sessions.session_events
                        where at >= :day and to_state in (:failed)
                        group by reason order by n desc, reason limit 5
                        """)
                .param("day", day)
                .param("failed", FAILED_STATES)
                .query((rs, n) -> new ControlRoom.Reason(rs.getString("reason"), rs.getLong("n")))
                .list();

        var models = new HashMap<String, String>();
        java.util.function.Function<Ran, String> model = ran -> models.computeIfAbsent(
                ran.hostId() + "/" + ran.gpuUuid(),
                key -> gpus.gpu(ran.hostId(), ran.gpuUuid())
                        .map(HostGpus.Gpu::model)
                        .orElse(ControlRoom.UNKNOWN_MODEL));
        var busy = new TreeMap<String, Double>();
        for (var ran : ran(now.minus(Duration.ofHours(24)), now)) {
            busy.merge(model.apply(ran), ran.gpus() * ControlRoom.hours(ran.from(), ran.to()), Double::sum);
        }
        var heat = new TreeMap<String, double[]>();
        for (var ran : ran(now.minus(Duration.ofDays(7)), now)) {
            ControlRoom.spreadByHour(
                    ran.from(), ran.to(), heat.computeIfAbsent(model.apply(ran), key -> new double[24]));
        }
        var demand = new ArrayList<ControlRoom.Cell>();
        heat.forEach((name, cells) -> {
            for (int hour = 0; hour < 24; hour++) {
                if (cells[hour] > 0) {
                    demand.add(new ControlRoom.Cell(name, hour, ControlRoom.round(cells[hour] / 7)));
                }
            }
        });
        return new ControlRoom.Marketplace(
                running,
                waiting,
                today,
                match[0],
                match[1],
                starts[0],
                starts[1],
                failures,
                busy.entrySet().stream()
                        .map(entry -> new ControlRoom.ByModel(entry.getKey(), ControlRoom.round(entry.getValue())))
                        .toList(),
                demand);
    }

    /** Every attempt's running stretch that overlaps {@code from} to {@code to}, cut to it. */
    private List<Ran> ran(Instant from, Instant to) {
        return jdbc
                .sql("""
                        select host_id, gpu_uuids[1] as gpu, cardinality(gpu_uuids) as gpus,
                               greatest(running_at, :from) as start, least(coalesce(ended_at, :to), :to) as finish
                        from sessions.attempts
                        where running_at is not null and running_at < :to and coalesce(ended_at, :to) > :from
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new Ran(
                        rs.getObject("host_id", UUID.class),
                        rs.getString("gpu"),
                        rs.getInt("gpus"),
                        rs.getTimestamp("start").toInstant(),
                        rs.getTimestamp("finish").toInstant()))
                .list()
                .stream()
                .filter(ran -> ran.to().isAfter(ran.from()))
                .toList();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Override
    public List<ControlRoom.Step> steps() {
        long first = count("""
                select count(*) from (
                    select renter_id from sessions.sessions where started_at is not null group by renter_id
                ) ran""");
        long second = count("""
                select count(*) from (
                    select renter_id from sessions.sessions where started_at is not null
                    group by renter_id having count(*) >= 2
                ) ran""");
        var hostsThatRan = jdbc.sql("select distinct host_id from sessions.attempts where running_at is not null")
                .query(UUID.class)
                .list();
        var ownersThatRan = new HashSet<UUID>();
        for (var host : hostsThatRan) {
            owners.ownerOf(host).ifPresent(ownersThatRan::add);
        }
        return List.of(
                new ControlRoom.Step("renters", 3, "first session", first),
                new ControlRoom.Step("renters", 4, "second session", second),
                new ControlRoom.Step("hosts", 3, "first session", ownersThatRan.size()));
    }
}
