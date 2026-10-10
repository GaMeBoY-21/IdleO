package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.hosts.HostTelemetry.Point;
import com.idleo.controlplane.hosts.HostTelemetry.Reading;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The last hour of a host's GPU readings, one point a minute, in a Valkey list per host (ADR 0023): newest first,
 * trimmed to 60 points, and gone an hour after the last write. Only the agent gateway writes it, from heartbeats. It
 * feeds the owner's machine screen and nothing else; billing never reads it.
 */
@Component
class TelemetryRing {

    static final String KEY_PREFIX = "agentgateway:telemetry:";
    static final int POINTS = 60;
    static final Duration INTERVAL = Duration.ofSeconds(60);
    static final Duration LIFETIME = Duration.ofHours(1);

    /** Adds a point unless the newest one is less than the interval old: 1 when added, 0 when it was too soon. */
    private static final RedisScript<Long> ADD = RedisScript.of("""
            local newest = redis.call('LINDEX', KEYS[1], 0)
            if newest and tonumber(ARGV[1]) - cjson.decode(newest)['at_ms'] < tonumber(ARGV[3]) then
              return 0
            end
            redis.call('LPUSH', KEYS[1], ARGV[2])
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[4]) - 1)
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    TelemetryRing(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** Records the readings as the host's point at {@code at}, if the last one is at least a minute older. */
    boolean record(UUID hostId, Instant at, List<Reading> readings) {
        var gpus = readings.stream()
                .map(reading -> Map.of(
                        "uuid", reading.gpuUuid(),
                        "temp_c", reading.tempC(),
                        "util_pct", reading.utilPct(),
                        "vram_used_mb", reading.vramUsedMb()))
                .toList();
        var point = json.writeValueAsString(Map.of("at_ms", at.toEpochMilli(), "gpus", gpus));
        Long added = redis.execute(
                ADD,
                List.of(key(hostId)),
                Long.toString(at.toEpochMilli()),
                point,
                Long.toString(INTERVAL.toMillis()),
                Integer.toString(POINTS),
                Long.toString(LIFETIME.toMillis()));
        return added != null && added == 1L;
    }

    /** The host's points, oldest first. */
    List<Point> points(UUID hostId) {
        var raw = redis.opsForList().range(key(hostId), 0, POINTS - 1);
        var points = new ArrayList<Point>();
        if (raw != null) {
            for (var entry : raw.reversed()) {
                var node = json.readTree(entry);
                var gpus = new ArrayList<Reading>();
                for (var gpu : node.get("gpus")) {
                    gpus.add(new Reading(
                            gpu.get("uuid").asString(),
                            gpu.get("temp_c").asInt(),
                            gpu.get("util_pct").asInt(),
                            gpu.get("vram_used_mb").asInt()));
                }
                points.add(new Point(Instant.ofEpochMilli(node.get("at_ms").asLong()), List.copyOf(gpus)));
            }
        }
        return points;
    }

    static String key(UUID hostId) {
        return KEY_PREFIX + hostId;
    }
}
