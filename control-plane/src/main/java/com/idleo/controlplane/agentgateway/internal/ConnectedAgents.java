package com.idleo.controlplane.agentgateway.internal;

import java.util.Map;
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
}
