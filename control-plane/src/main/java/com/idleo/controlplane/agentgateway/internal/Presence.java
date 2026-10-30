package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.agentgateway.AgentPresence;
import com.idleo.controlplane.hosts.HostPresence;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Host presence in Valkey (ADR 0011): one hash per host, living for the presence TTL after the last
 * heartbeat. Only the agent gateway writes it. Each record names the connection and instance that
 * own it, and every write is conditional on that, so an old stream's late heartbeat or cleanup can
 * never touch a newer stream's record.
 *
 * <p>It also answers the hosts module's {@link HostPresence} port: a host is online while its record
 * exists (ADR 0013).
 */
@Component
@EnableConfigurationProperties(AgentGatewayProperties.class)
class Presence implements HostPresence, AgentPresence {

    static final String KEY_PREFIX = "agentgateway:presence:";

    /** A new stream owns the host's presence: replace whatever was there. */
    private static final RedisScript<Long> REGISTER = RedisScript.of("""
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], unpack(ARGV, 2))
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    /**
     * Refresh, if the record is still this connection's: 1 when written, 0 when another connection
     * owns it. A record that expired while the stream was still open is written again.
     */
    private static final RedisScript<Long> REFRESH = RedisScript.of("""
            local owner = redis.call('HGET', KEYS[1], 'connection_id')
            if owner and owner ~= ARGV[2] then
              return 0
            end
            redis.call('HSET', KEYS[1], unpack(ARGV, 3))
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    /** Compare-and-delete: remove the record only if this connection still owns it. */
    private static final RedisScript<Long> REMOVE = RedisScript.of("""
            if redis.call('HGET', KEYS[1], 'connection_id') == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(Presence.class);

    private final StringRedisTemplate redis;
    private final AgentGatewayProperties properties;
    private final AgentGatewayMetrics metrics;

    private final JsonMapper json;

    Presence(
            StringRedisTemplate redis,
            AgentGatewayProperties properties,
            AgentGatewayMetrics metrics,
            JsonMapper json) {
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
        this.json = json;
    }

    /** One round trip for all the hosts. If Valkey can't answer, none is online, and the miss is counted. */
    @Override
    public Set<UUID> online(Collection<UUID> hostIds) {
        var ids = List.copyOf(hostIds);
        try {
            var exists = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (var id : ids) {
                    connection.keyCommands().exists(key(id).getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            var online = new HashSet<UUID>();
            for (int i = 0; i < ids.size(); i++) {
                if (Boolean.TRUE.equals(exists.get(i))) {
                    online.add(ids.get(i));
                }
            }
            return online;
        } catch (RuntimeException e) {
            metrics.presenceUnavailable();
            log.debug("Presence lookup failed; reporting {} hosts offline", ids.size(), e);
            return Set.of();
        }
    }

    @Override
    public Map<UUID, Connection> connections(Collection<UUID> hostIds) {
        var connections = new java.util.HashMap<UUID, Connection>();
        live(hostIds)
                .forEach((hostId, agent) ->
                        connections.put(hostId, new Connection(agent.connectedAt(), agent.updateRequired())));
        return connections;
    }

    /** What a presence record holds. */
    record Snapshot(
            UUID hostId,
            UUID connectionId,
            UUID instanceId,
            Instant connectedAt,
            String agentVersion,
            boolean updateRequired,
            boolean ownerActive,
            int netUpKbps,
            @Nullable Integer freeDiskMb,
            String gpusJson,
            List<String> cachedImages,
            Instant heartbeatAt) {

        /** Every field; free disk is empty until a heartbeat reports it. */
        Map<String, String> fields() {
            return Map.ofEntries(
                    Map.entry("cached_images", String.join(",", cachedImages)),
                    Map.entry("free_disk_mb", freeDiskMb == null ? "" : Integer.toString(freeDiskMb)),
                    Map.entry("connection_id", connectionId.toString()),
                    Map.entry("instance_id", instanceId.toString()),
                    Map.entry("connected_at", connectedAt.toString()),
                    Map.entry("agent_version", agentVersion),
                    Map.entry("update_required", Boolean.toString(updateRequired)),
                    Map.entry("owner_active", Boolean.toString(ownerActive)),
                    Map.entry("net_up_kbps", Integer.toString(netUpKbps)),
                    Map.entry("gpus", gpusJson),
                    Map.entry("heartbeat_at", heartbeatAt.toString()));
        }
    }

    /** The instance holding the host's stream, if it has one. */
    Optional<UUID> instanceOf(UUID hostId) {
        Object instance = redis.opsForHash().get(key(hostId), "instance_id");
        return instance == null ? Optional.empty() : Optional.of(UUID.fromString(instance.toString()));
    }

    /** One round trip for all the hosts. If Valkey can't answer, presence is unknown, and the miss is counted. */
    @Override
    public Lookup lookup(Collection<UUID> hostIds) {
        var ids = List.copyOf(hostIds);
        if (ids.isEmpty()) {
            return new Lookup(Map.of(), true);
        }
        try {
            var records = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (var id : ids) {
                    connection.hashCommands().hGetAll(key(id).getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            var live = new java.util.HashMap<UUID, LiveAgent>();
            for (int i = 0; i < ids.size(); i++) {
                if (records.get(i) instanceof Map<?, ?> fields && !fields.isEmpty()) {
                    live.put(ids.get(i), liveAgent(ids.get(i), fields));
                }
            }
            return new Lookup(live, true);
        } catch (RuntimeException e) {
            metrics.presenceUnavailable();
            log.debug("Presence lookup failed; presence is unknown", e);
            return new Lookup(Map.of(), false);
        }
    }

    private LiveAgent liveAgent(UUID hostId, Map<?, ?> fields) {
        var temps = new ArrayList<Integer>();
        var readings = new ArrayList<GpuReading>();
        var gpus = text(fields, "gpus");
        if (!gpus.isEmpty()) {
            for (var reading : json.readTree(gpus)) {
                if (reading.has("temp_c")) {
                    temps.add(reading.get("temp_c").asInt());
                }
                if (reading.has("uuid") && reading.has("temp_c") && reading.has("util_pct")) {
                    readings.add(new GpuReading(
                            reading.get("uuid").asString(),
                            reading.get("util_pct").asInt(),
                            reading.get("temp_c").asInt(),
                            reading.has("vram_used_mb")
                                    ? reading.get("vram_used_mb").asInt()
                                    : 0));
                }
            }
        }
        var freeDisk = text(fields, "free_disk_mb");
        return new LiveAgent(
                hostId,
                Instant.parse(text(fields, "connected_at")),
                Boolean.parseBoolean(text(fields, "update_required")),
                Boolean.parseBoolean(text(fields, "owner_active")),
                freeDisk.isEmpty() ? null : Integer.valueOf(freeDisk),
                List.copyOf(temps),
                List.copyOf(readings),
                text(fields, "cached_images").isEmpty()
                        ? List.of()
                        : List.of(text(fields, "cached_images").split(",")));
    }

    private static String text(Map<?, ?> fields, String name) {
        for (var entry : fields.entrySet()) {
            var key = entry.getKey() instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : String.valueOf(entry.getKey());
            if (key.equals(name)) {
                return entry.getValue() instanceof byte[] bytes
                        ? new String(bytes, StandardCharsets.UTF_8)
                        : String.valueOf(entry.getValue());
            }
        }
        return "";
    }

    void register(Snapshot snapshot) {
        var args = new ArrayList<String>();
        args.add(ttlMillis());
        snapshot.fields().forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        redis.execute(REGISTER, List.of(key(snapshot.hostId())), args.toArray());
    }

    /** False if another connection now owns the host's presence. */
    boolean refresh(Snapshot snapshot) {
        var args = new ArrayList<String>();
        args.add(ttlMillis());
        args.add(snapshot.connectionId().toString());
        snapshot.fields().forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        Long written = redis.execute(REFRESH, List.of(key(snapshot.hostId())), args.toArray());
        return written != null && written == 1L;
    }

    void remove(UUID hostId, UUID connectionId) {
        redis.execute(REMOVE, List.of(key(hostId)), connectionId.toString());
    }

    static String key(UUID hostId) {
        return KEY_PREFIX + hostId;
    }

    private String ttlMillis() {
        return Long.toString(properties.presenceTtl().toMillis());
    }
}
