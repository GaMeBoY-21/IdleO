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

    /**
     * Records the host's latest self-test, replacing the last. With {@code hosts.auto-activate} on, a pending host
     * whose self-test passed is activated, through the same service as the admin console's Approve.
     */
    void recordSelfTest(UUID hostId, SelfTest selfTest);

    /** Records how an update went, the latest per version; a version rolled back is never offered to the host again. */
    void recordUpdateResult(UUID hostId, String version, UpdateOutcome outcome, String detail);

    /** A self-test: every check that ran, whether all passed, and the distro's version (empty without one). */
    record SelfTest(boolean passed, String distroVersion, List<SelfTestCheck> checks) {

        public SelfTest {
            checks = List.copyOf(checks);
        }
    }

    /** One check of a self-test. */
    record SelfTestCheck(String name, boolean passed, String detail, long tookMs) {}

    /** How an offered update ended. */
    enum UpdateOutcome {
        INSTALLED,
        ROLLED_BACK,
        FAILED_VERIFY,
        FAILED_DOWNLOAD
    }

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
