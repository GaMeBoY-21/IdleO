package com.idleo.controlplane.hosts;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a host's agent reports about the machine, recorded by the hosts module (ADR 0011). The agent
 * gateway validates the protocol messages and calls this; it never touches hosts tables.
 */
public interface HostAgentReports {

    /**
     * Records a Hello: the host's OS and agent version, and its GPUs, upserted by GPU uuid. A GPU
     * the host reported before but not this time is marked gone, not deleted.
     */
    void recordHello(UUID hostId, AgentHello hello);

    /**
     * Records that the host's agent was seen now. Writes at most once a minute per host. Returns false when the
     * owner has removed the host (ADR 0023) or an admin has revoked its certificates (ADR 0027), so a stream held by
     * another instance closes within a minute.
     */
    boolean recordSeen(UUID hostId);

    /** A validated Hello. */
    record AgentHello(String os, String agentVersion, List<ReportedGpu> gpus) {

        public AgentHello {
            gpus = List.copyOf(gpus);
        }
    }

    /** One GPU as the agent reports it. */
    record ReportedGpu(
            String uuid,
            String model,
            int vramMb,
            @Nullable String driverVersion,
            @Nullable String cudaDriverVersion,
            @Nullable String computeCapability) {}
}
