package com.idleo.controlplane.agentgateway;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may know about live agents, from their presence records (ADR 0015): the scheduler's filters,
 * and a running session's GPU readings (ADR 0021). A record lives only while heartbeats arrive, so whatever it holds
 * is current.
 */
public interface AgentPresence {

    /**
     * One GPU's latest reading.
     *
     * @param utilPct how busy it is, 0 to 100
     */
    record GpuReading(String gpuUuid, int utilPct, int tempC) {}

    /**
     * One live agent.
     *
     * @param gpuTempsC the latest temperature of each GPU, empty before the first heartbeat
     * @param gpus the latest reading of each GPU, empty before the first heartbeat
     */
    record LiveAgent(
            UUID hostId, boolean updateRequired, boolean ownerActive, List<Integer> gpuTempsC, List<GpuReading> gpus) {

        /** The reading of one of its GPUs, if the last heartbeat had one. */
        public Optional<GpuReading> gpu(String gpuUuid) {
            return gpus.stream()
                    .filter(reading -> reading.gpuUuid().equals(gpuUuid))
                    .findFirst();
        }
    }

    /** The live agents among {@code hostIds}; empty if presence can't be read. */
    Map<UUID, LiveAgent> live(Collection<UUID> hostIds);
}
