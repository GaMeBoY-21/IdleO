package com.idleo.controlplane.agentgateway.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.hosts.HostTelemetry.Reading;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The last hour of GPU readings (ADR 0023): a point a minute, 60 at most, in Valkey. */
@ControlPlaneTest
class TelemetryRingTests {

    @Autowired
    TelemetryRing ring;

    @Autowired
    StringRedisTemplate redis;

    private static List<Reading> readings(int tempC) {
        return List.of(new Reading("GPU-1", tempC, 50, 2_048));
    }

    @Test
    void itKeepsTheLast60PointsOldestFirst() {
        var host = UUID.randomUUID();
        var start = Instant.parse("2026-10-10T10:00:00Z");

        for (int minute = 0; minute < 75; minute++) {
            assertThat(ring.record(host, start.plus(Duration.ofMinutes(minute)), readings(40 + minute % 40)))
                    .isTrue();
        }

        var points = ring.points(host);
        assertThat(points).hasSize(60);
        assertThat(points.getFirst().at()).isEqualTo(start.plus(Duration.ofMinutes(15)));
        assertThat(points.getLast().at()).isEqualTo(start.plus(Duration.ofMinutes(74)));
        assertThat(points.getLast().gpus()).containsExactly(new Reading("GPU-1", 40 + 74 % 40, 50, 2_048));
        assertThat(points).extracting(point -> point.at()).isSorted();
        assertThat(redis.getExpire(TelemetryRing.key(host))).isPositive().isLessThanOrEqualTo(3_600);
    }

    @Test
    void aPointLessThanAMinuteAfterTheLastIsNotKept() {
        var host = UUID.randomUUID();
        var start = Instant.parse("2026-10-10T10:00:00Z");

        assertThat(ring.record(host, start, readings(50))).isTrue();
        assertThat(ring.record(host, start.plusSeconds(59), readings(51))).isFalse();
        assertThat(ring.record(host, start.plusSeconds(60), readings(52))).isTrue();

        assertThat(ring.points(host)).hasSize(2);
        assertThat(ring.points(host).getLast().gpus().getFirst().tempC()).isEqualTo(52);
    }

    @Test
    void aHostWithNoPointsHasAnEmptyHistory() {
        assertThat(ring.points(UUID.randomUUID())).isEmpty();
    }
}
