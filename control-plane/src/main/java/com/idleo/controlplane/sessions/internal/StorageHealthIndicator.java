package com.idleo.controlplane.sessions.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Whether the sessions' bucket answers with the control plane's own key: the health component {@code storage}, and the
 * meter {@code storage.reachable} (1 or 0), which the storage-unreachable alert reads (ADR 0036). It is not part of
 * readiness: without storage no upload can be opened and no output or log read, and those calls answer a plain 503
 * ({@code storage-unavailable}), but sessions, leases and everything else go on, so an instance keeps its traffic.
 * Storage is asked at most once every {@link #RECHECK_EVERY} and the answer kept until then.
 */
@Component
class StorageHealthIndicator implements HealthIndicator {

    static final Duration RECHECK_EVERY = Duration.ofSeconds(10);

    private final S3Client s3;
    private final StorageProperties properties;
    private final Clock clock;

    private record Checked(Health health, Instant at) {}

    private volatile @Nullable Checked last;

    StorageHealthIndicator(S3Client sessionS3, StorageProperties properties, Clock clock, MeterRegistry registry) {
        this.s3 = sessionS3;
        this.properties = properties;
        this.clock = clock;
        Gauge.builder(
                        "storage.reachable",
                        this,
                        indicator -> Status.UP.equals(indicator.health().getStatus()) ? 1 : 0)
                .description("1 while the sessions' object storage answers, 0 while it doesn't")
                .register(registry);
    }

    @Override
    public Health health() {
        var now = Instant.now(clock);
        var checked = last;
        if (checked == null || !now.isBefore(checked.at().plus(RECHECK_EVERY))) {
            checked = new Checked(check(), now);
            last = checked;
        }
        return checked.health();
    }

    private Health check() {
        try {
            s3.headBucket(request -> request.bucket(properties.bucket()));
            return Health.up().build();
        } catch (RuntimeException unreachable) {
            // The kind of failure only: a message could carry the endpoint or a key's id.
            return Health.down()
                    .withDetail("error", unreachable.getClass().getSimpleName())
                    .build();
        }
    }
}
