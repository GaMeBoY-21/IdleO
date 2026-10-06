package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.agentgateway.AgentStreams;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class LocalAgentStreams implements AgentStreams {

    private final ConnectedAgents connected;
    private final AgentGatewayProperties properties;
    private final IdGenerator ids;
    private final Clock clock;

    LocalAgentStreams(ConnectedAgents connected, AgentGatewayProperties properties, IdGenerator ids, Clock clock) {
        this.connected = connected;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Set<UUID> liveHere() {
        var freshAfter = freshAfter();
        return connected.hostIds().stream()
                .filter(hostId -> {
                    var stream = connected.get(hostId);
                    return stream != null && stream.heardFromAfter(freshAfter);
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean sendIfLive(UUID hostId, OpenStreamResponse.Builder message) {
        var stream = connected.get(hostId);
        if (stream == null || !stream.heardFromAfter(freshAfter())) {
            return false;
        }
        return stream.deliver(message.setMessageId(ids.newId().toString()).build());
    }

    private Instant freshAfter() {
        return Instant.now(clock).minus(properties.presenceTtl());
    }
}
