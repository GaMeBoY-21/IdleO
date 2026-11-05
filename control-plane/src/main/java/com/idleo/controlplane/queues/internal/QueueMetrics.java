package com.idleo.controlplane.queues.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the inbox's dashboards and alerts read (ADR 0053): open items, the oldest's age and those past their target, by
 * type; breaches, time to first reply and time to resolve; and how many chargebacks are inside their paging window.
 */
@Component
class QueueMetrics {

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final MultiGauge open;
    private final MultiGauge oldest;
    private final MultiGauge breaching;
    private final AtomicLong chargebacksDue;
    private final AtomicLong lastPass;

    QueueMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.clock = clock;
        this.open = MultiGauge.builder("queues.items.open")
                .description("Items open, by type")
                .register(registry);
        this.oldest = MultiGauge.builder("queues.items.oldest.age")
                .baseUnit("seconds")
                .description("How long the oldest open item has been open, by type")
                .register(registry);
        this.breaching = MultiGauge.builder("queues.items.breached")
                .description("Open items past their target, by type")
                .register(registry);
        this.chargebacksDue = registry.gauge("queues.chargebacks.due_soon", new AtomicLong());
        this.lastPass = registry.gauge("queues.sync.last_pass", new AtomicLong(clock.millis() / 1000));
    }

    /** Asks the database, once a pass. */
    void refresh() {
        record Row(String type, long open, double oldest, long breached) {}
        var now = Timestamp.from(clock.instant());
        var rows = jdbc.sql("""
                        select type, count(*) as open,
                               extract(epoch from :now - min(opened_at)) as oldest,
                               count(*) filter (where breached_at is not null) as breached
                        from queues.items where state <> 'resolved' group by type
                        """)
                .param("now", now)
                .query((rs, n) -> new Row(
                        rs.getString("type"), rs.getLong("open"), rs.getDouble("oldest"), rs.getLong("breached")))
                .list();
        this.open.register(
                rows.stream()
                        .map(row -> MultiGauge.Row.of(Tags.of("type", row.type()), row.open()))
                        .toList(),
                true);
        this.oldest.register(
                rows.stream()
                        .map(row -> MultiGauge.Row.of(Tags.of("type", row.type()), row.oldest()))
                        .toList(),
                true);
        this.breaching.register(
                rows.stream()
                        .map(row -> MultiGauge.Row.of(Tags.of("type", row.type()), row.breached()))
                        .toList(),
                true);
        chargebacksDue.set(jdbc.sql("""
                        select count(*) from queues.items
                        where state <> 'resolved' and type = 'chargeback' and soon_at <= :now
                        """).param("now", now).query(Long.class).single());
        lastPass.set(clock.millis() / 1000);
    }

    void breached(String type) {
        registry.counter("queues.breaches", List.of(io.micrometer.core.instrument.Tag.of("type", type)))
                .increment();
    }

    void failed(String type) {
        registry.counter("queues.sync.failures", List.of(io.micrometer.core.instrument.Tag.of("type", type)))
                .increment();
    }

    void firstReply(String type, Duration took) {
        registry.timer("queues.first_reply", "type", type).record(took);
    }

    void resolved(String type, Duration took) {
        registry.timer("queues.resolve", "type", type).record(took);
    }
}
