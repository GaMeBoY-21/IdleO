package com.idleo.controlplane.agentgateway.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Host presence in Valkey (ADR 0011): one hash per host, living for the presence TTL after the last
 * heartbeat. Only the agent gateway writes it. Each record names the connection and instance that
 * own it, and every write is conditional on that, so an old stream's late heartbeat or cleanup can
 * never touch a newer stream's record.
 */
@Component
class Presence {

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

    private final StringRedisTemplate redis;
    private final AgentGatewayProperties properties;

    Presence(StringRedisTemplate redis, AgentGatewayProperties properties) {
        this.redis = redis;
        this.properties = properties;
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
            String gpusJson,
            Instant heartbeatAt) {

        Map<String, String> fields() {
            return Map.of(
                    "connection_id", connectionId.toString(),
                    "instance_id", instanceId.toString(),
                    "connected_at", connectedAt.toString(),
                    "agent_version", agentVersion,
                    "update_required", Boolean.toString(updateRequired),
                    "owner_active", Boolean.toString(ownerActive),
                    "net_up_kbps", Integer.toString(netUpKbps),
                    "gpus", gpusJson,
                    "heartbeat_at", heartbeatAt.toString());
        }
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
