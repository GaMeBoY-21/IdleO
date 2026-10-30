package com.idleo.controlplane.agentgateway;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
     * @param vramUsedMb the GPU memory in use
     */
    record GpuReading(String gpuUuid, int utilPct, int tempC, int vramUsedMb) {}

    /**
     * One live agent.
     *
     * @param freeDiskMb free space where sessions' files go (ADR 0022); null before the first heartbeat, or when the
     *     agent couldn't read it
     * @param gpuTempsC the latest temperature of each GPU, empty before the first heartbeat
     * @param gpus the latest reading of each GPU, empty before the first heartbeat
     * @param cachedImages the job images already on the machine, as digests ("sha256:..."), from the last heartbeat;
     *     empty before the first, or when the agent reports none
     * @param connectedAt when its current stream opened
     */
    record LiveAgent(
            UUID hostId,
            Instant connectedAt,
            boolean updateRequired,
            boolean ownerActive,
            @Nullable Integer freeDiskMb,
            List<Integer> gpuTempsC,
            List<GpuReading> gpus,
            List<String> cachedImages) {

        /** The reading of one of its GPUs, if the last heartbeat had one. */
        public Optional<GpuReading> gpu(String gpuUuid) {
            return gpus.stream()
                    .filter(reading -> reading.gpuUuid().equals(gpuUuid))
                    .findFirst();
        }
    }

    /** Where a host stands: its agent's record is live, or isn't, or presence couldn't be read. */
    enum State {
        ONLINE,
        OFFLINE,
        UNKNOWN
    }

    /**
     * One presence lookup.
     *
     * @param agents the live agents among the hosts asked about
     * @param known presence was read: false when Valkey couldn't answer, which says nothing about whether a host is
     *     online, so no host is offline because of it (the scheduler tries again, SessionMatcher)
     */
    record Lookup(Map<UUID, LiveAgent> agents, boolean known) {

        public State state(UUID hostId) {
            if (!known) {
                return State.UNKNOWN;
            }
            return agents.containsKey(hostId) ? State.ONLINE : State.OFFLINE;
        }
    }

    /** The live agents among {@code hostIds}, in one round trip; {@code known} false if presence can't be read. */
    Lookup lookup(Collection<UUID> hostIds);

    /** The live agents among {@code hostIds}, for a view of what's known now: empty if presence can't be read. */
    default Map<UUID, LiveAgent> live(Collection<UUID> hostIds) {
        return lookup(hostIds).agents();
    }
}
