package com.idleo.controlplane.agentgateway;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What the scheduler needs to know about live agents, from their presence records (ADR 0015). */
public interface AgentPresence {

    /**
     * One live agent.
     *
     * @param gpuTempsC the latest temperature of each GPU, empty before the first heartbeat
     */
    record LiveAgent(UUID hostId, boolean updateRequired, boolean ownerActive, List<Integer> gpuTempsC) {}

    /** The live agents among {@code hostIds}; empty if presence can't be read. */
    Map<UUID, LiveAgent> live(Collection<UUID> hostIds);
}
