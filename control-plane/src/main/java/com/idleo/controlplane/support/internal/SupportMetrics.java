package com.idleo.controlplane.support.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the support alerts read (ADR 0037): {@code support.tickets.due_soon}, the privacy requests and grievances not
 * resolved whose due date is within 7 days or past, and {@code support.tickets.overdue}, those past it. Asked once a
 * minute, when the meters are sent.
 */
@Component
class SupportMetrics {

    static final Duration SOON = Duration.ofDays(7);

    SupportMetrics(JdbcClient jdbc, Clock clock, MeterRegistry registry) {
        Gauge.builder(
                        "support.tickets.due_soon",
                        () -> count(jdbc, Instant.now(clock).plus(SOON)))
                .description("Privacy requests and grievances not resolved, due within 7 days or past due")
                .register(registry);
        Gauge.builder("support.tickets.overdue", () -> count(jdbc, Instant.now(clock)))
                .description("Privacy requests and grievances not resolved, past their due date")
                .register(registry);
    }

    private static double count(JdbcClient jdbc, Instant before) {
        return jdbc.sql("select count(*) from support.tickets where status <> 'resolved' and due_at < :before")
                .param("before", Timestamp.from(before))
                .query(Long.class)
                .single();
    }
}
