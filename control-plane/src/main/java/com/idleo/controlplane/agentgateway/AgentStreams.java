package com.idleo.controlplane.agentgateway;

import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import java.util.Set;
import java.util.UUID;

/**
 * The agent streams this instance holds (ADR 0016), for messages that are never queued because the next
 * one is moments away, such as LeaseRenew. A stream counts as live while its heartbeats are fresh.
 */
public interface AgentStreams {

    /** Hosts whose stream this instance holds and whose last heartbeat is within the presence TTL. */
    Set<UUID> liveHere();

    /**
     * Sends {@code message} down the host's stream, with a new message id, if this instance holds it and it's
     * live; false otherwise, and nothing is sent.
     */
    boolean sendIfLive(UUID hostId, OpenStreamResponse.Builder message);
}
