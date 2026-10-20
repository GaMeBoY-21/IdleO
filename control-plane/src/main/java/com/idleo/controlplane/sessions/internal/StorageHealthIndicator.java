package com.idleo.controlplane.sessions.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Readiness's {@code storage} component: whether the session bucket answers with the control plane's own key. Without
 * it no session can start, no checkpoint or output be found, and no upload be opened, so an instance that can't reach
 * it takes no traffic. A readiness probe comes every few seconds from each load balancer, so storage is asked at most
 * once every {@link #RECHECK_EVERY} and the answer kept until then.
 */
@Component
class StorageHealthIndicator implements HealthIndicator {

    static final Duration RECHECK_EVERY = Duration.ofSeconds(10);

    private final S3Client s3;
    private final StorageProperties properties;
    private final Clock clock;

    private record Checked(Health health, Instant at) {}

    private volatile @Nullable Checked last;

    StorageHealthIndicator(S3Client sessionS3, StorageProperties properties, Clock clock) {
        this.s3 = sessionS3;
        this.properties = properties;
        this.clock = clock;
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
