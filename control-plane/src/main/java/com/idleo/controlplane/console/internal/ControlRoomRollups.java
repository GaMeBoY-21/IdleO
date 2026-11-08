package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.ControlRoom;
import com.idleo.controlplane.OneAtATime;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The live dashboard's rollups (ADR 0056): one job, on one instance at a time, asks each module's {@link ControlRoom}
 * part and keeps what it gives in {@code console.rollups}, which every page view reads. The health checks run every 10
 * s, every other part every minute; a part that fails keeps its last rollup, which says when it was worked out.
 */
@Component
class ControlRoomRollups {

    private static final Logger log = LoggerFactory.getLogger(ControlRoomRollups.class);

    /** One part: its name, how often it's worked out, and how. */
    record Part(String name, Duration every, Supplier<@Nullable Object> work) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final OneAtATime oneAtATime;
    private final MeterRegistry meters;
    private final Clock clock;
    private final List<Part> parts;

    ControlRoomRollups(
            JdbcClient jdbc,
            JsonMapper json,
            OneAtATime oneAtATime,
            MeterRegistry meters,
            Clock clock,
            ObjectProvider<ControlRoom.MoneySource> money,
            ObjectProvider<ControlRoom.MarketplaceSource> marketplace,
            ObjectProvider<ControlRoom.SupplySource> supply,
            ObjectProvider<ControlRoom.FunnelSource> funnels,
            ObjectProvider<ControlRoom.HealthSource> health) {
        this.jdbc = jdbc;
        this.json = json;
        this.oneAtATime = oneAtATime;
        this.meters = meters;
        this.clock = clock;
        var minute = Duration.ofMinutes(1);
        this.parts = List.of(
                new Part("money", minute, () -> one(money, source -> source.money(clock.instant()))),
                new Part("marketplace", minute, () -> one(marketplace, source -> source.marketplace(clock.instant()))),
                new Part("supply", minute, () -> one(supply, source -> source.supply(clock.instant()))),
                new Part(
                        "funnels",
                        minute,
                        () -> funnels.orderedStream()
                                .flatMap(source -> source.steps().stream())
                                .sorted(Comparator.comparing(ControlRoom.Step::funnel)
                                        .thenComparing(ControlRoom.Step::order))
                                .toList()),
                new Part(
                        "health",
                        Duration.ofSeconds(10),
                        () -> health.orderedStream()
                                .flatMap(source -> source.checks(clock.instant()).stream())
                                .toList()));
    }

    private static <S, T> @Nullable T one(ObjectProvider<S> sources, java.util.function.Function<S, T> work) {
        var source = sources.getIfUnique();
        return source == null ? null : work.apply(source);
    }

    List<Part> parts() {
        return parts;
    }

    /** Every 10 s, on one instance at a time: the parts that are due. */
    @Scheduled(fixedDelayString = "${console.rollups.tick:10s}", initialDelayString = "PT5S")
    void tick() {
        try {
            oneAtATime.run("console:control-room", () -> rollUp(false));
        } catch (RuntimeException e) {
            log.warn("The dashboard's rollups failed; they run again shortly", e);
        }
    }

    /** Works out the parts that are due, or all of them; how long it took, in milliseconds. */
    long rollUp(boolean all) {
        long started = System.nanoTime();
        var now = clock.instant();
        var computed = new HashMap<String, Instant>();
        jdbc.sql("select part, computed_at from console.rollups").query(rs -> {
            computed.put(rs.getString("part"), rs.getTimestamp("computed_at").toInstant());
        });
        for (var part : parts) {
            var last = computed.get(part.name());
            // A little before its time, so a tick that runs late doesn't skip a whole turn.
            if (!all && last != null && last.plus(part.every()).minusSeconds(2).isAfter(now)) {
                continue;
            }
            long partStarted = System.nanoTime();
            try {
                var body = part.work().get();
                long took = (System.nanoTime() - partStarted) / 1_000_000;
                jdbc.sql("""
                                insert into console.rollups (part, body, took_ms, computed_at)
                                values (:part, cast(:body as jsonb), :took, :at)
                                on conflict (part) do update
                                set body = excluded.body, took_ms = excluded.took_ms, computed_at = excluded.computed_at
                                """)
                        .param("part", part.name())
                        .param("body", json.writeValueAsString(body))
                        .param("took", (int) took)
                        .param("at", Timestamp.from(now))
                        .update();
                meters.timer("console.rollup", "part", part.name()).record(Duration.ofMillis(took));
            } catch (RuntimeException e) {
                log.warn("The dashboard's {} part failed; it keeps its last rollup", part.name(), e);
            }
        }
        return (System.nanoTime() - started) / 1_000_000;
    }

    /** The rollups as kept: each part's body as JSON text, and when it was worked out. */
    record Kept(Map<String, String> bodies, Map<String, Instant> computedAt) {}

    Kept kept() {
        var bodies = new LinkedHashMap<String, String>();
        var computedAt = new LinkedHashMap<String, Instant>();
        jdbc.sql("select part, body::text as body, computed_at from console.rollups order by part")
                .query(rs -> {
                    bodies.put(rs.getString("part"), rs.getString("body"));
                    computedAt.put(
                            rs.getString("part"), rs.getTimestamp("computed_at").toInstant());
                });
        return new Kept(bodies, computedAt);
    }
}
