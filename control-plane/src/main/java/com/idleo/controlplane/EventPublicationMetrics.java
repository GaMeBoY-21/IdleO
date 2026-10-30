package com.idleo.controlplane;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Event publications that haven't reached their listener (ADR 0036), from Spring Modulith's registry
 * ({@code modulith.event_publication}, ADR 0015): {@code events.publications.failed}, those whose listener threw and
 * wait for {@link EventPublicationRetry}, and {@code events.publications.stuck}, any not completed after
 * {@link #STUCK_AFTER}, which retries alone won't explain; and {@code events.publications.failed_permanently}, those
 * that used up their retries ({@link EventPublicationRetry#MAX_ATTEMPTS}) and wait for a person. Worked out from the database each time they're read, once
 * a minute, so every instance reports the same figures and a dashboard takes the max.
 */
@Component
class EventPublicationMetrics {

    static final Duration STUCK_AFTER = Duration.ofMinutes(10);

    EventPublicationMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        Gauge.builder(
                        "events.publications.failed",
                        () -> jdbc.sql("""
                                select count(*) from modulith.event_publication
                                where completion_date is null and status = 'FAILED'
                                """).query(Long.class).single())
                .description("Event publications whose listener failed, waiting to be retried")
                .register(registry);
        Gauge.builder(
                        "events.publications.stuck",
                        () -> jdbc.sql("""
                                select count(*) from modulith.event_publication
                                where completion_date is null and publication_date < :before
                                """)
                                .param(
                                        "before",
                                        Timestamp.from(Instant.now(clock).minus(STUCK_AFTER)))
                                .query(Long.class)
                                .single())
                .description("Event publications not completed ten minutes after they were made")
                .register(registry);
        Gauge.builder(
                        "events.publications.failed_permanently",
                        () -> jdbc.sql("""
                                select count(*) from modulith.event_publication
                                where completion_date is null and completion_attempts >= :attempts
                                """)
                                .param("attempts", EventPublicationRetry.MAX_ATTEMPTS)
                                .query(Long.class)
                                .single())
                .description("Event publications that failed every retry and are no longer retried")
                .register(registry);
    }
}
