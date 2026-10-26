package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.AfterCommit;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The image checks' meters (ADR 0042, ADR 0036), each counted after its transaction commits, tagged only by a fixed
 * outcome or reason: checks submitted, checks ended (ready, blocked, failed), answered from a cached scan, and blocks of
 * images that were already checked (a newer blocklist, an admin). {@code images.checks.backlog.seconds} is how long the
 * oldest check that isn't done has waited; the alert ImageCheckBacklog fires above 30 minutes.
 */
@Component
class ImagesMetrics {

    private final MeterRegistry registry;

    ImagesMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock) {
        this.registry = registry;
        Gauge.builder("images.checks.backlog.seconds", () -> backlog(jdbc, clock))
                .description("How long the oldest image check still queued or being checked has waited")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(
                        "images.checks.waiting",
                        () -> jdbc.sql("select count(*) from images.images where state in ('queued', 'checking')")
                                .query(Long.class)
                                .single())
                .description("Image checks queued or being checked")
                .register(registry);
    }

    private static double backlog(JdbcClient jdbc, Clock clock) {
        var oldest = jdbc.sql("select min(created_at) from images.images where state in ('queued', 'checking')")
                .query(Timestamp.class)
                .optional();
        return oldest.map(at -> (double)
                        Duration.between(at.toInstant(), Instant.now(clock)).toSeconds())
                .orElse(0.0);
    }

    void submitted() {
        AfterCommit.run(() -> registry.counter("images.checks.submitted").increment());
    }

    /** A check ended: {@code ready}, {@code blocked} or {@code failed}. */
    void ended(String outcome) {
        AfterCommit.run(() ->
                registry.counter("images.checks.ended", "outcome", outcome).increment());
    }

    /** A check answered from a scan of the same digest. */
    void cached() {
        AfterCommit.run(() -> registry.counter("images.checks.cached").increment());
    }

    /** Submissions blocked after their check, by {@code blocklisted} or {@code admin}. */
    void newlyBlocked(String reason, int count) {
        if (count > 0) {
            AfterCommit.run(
                    () -> registry.counter("images.blocked", "reason", reason).increment(count));
        }
    }
}
