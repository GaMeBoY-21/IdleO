package com.idleo.controlplane.agentgateway.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The agent streams open on this instance, one per host. */
@Component
class ConnectedAgents {

    private final Map<UUID, AgentStream> streams = new ConcurrentHashMap<>();

    /** Registers the stream for its host and returns the stream it replaced, if any. */
    @Nullable
    AgentStream register(AgentStream stream) {
        return streams.put(stream.hostId(), stream);
    }

    /** Removes the stream, but only if it is still the host's current one. */
    void remove(AgentStream stream) {
        streams.remove(stream.hostId(), stream);
    }

    int size() {
        return streams.size();
    }

    @Nullable
    AgentStream get(UUID hostId) {
        return streams.get(hostId);
    }

    /** The longest any open stream here has gone without a Hello or a valid heartbeat; zero with none. */
    Duration longestSilence(Instant now) {
        return streams.values().stream()
                .map(AgentStream::lastHeardAt)
                .filter(Objects::nonNull)
                .map(heard -> Duration.between(heard, now))
                .max(Comparator.naturalOrder())
                .orElse(Duration.ZERO);
    }

    java.util.Set<UUID> hostIds() {
        return java.util.Set.copyOf(streams.keySet());
    }
}
