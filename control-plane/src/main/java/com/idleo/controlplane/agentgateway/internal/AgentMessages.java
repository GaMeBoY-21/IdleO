package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.hosts.HostAgentReports.AgentHello;
import com.idleo.controlplane.hosts.HostAgentReports.ReportedGpu;
import gpu.agent.v1.AgentGateway.Hello;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Telemetry.GpuTelemetry;
import gpu.agent.v1.Telemetry.Heartbeat;
import gpu.agent.v1.Telemetry.OperatingSystem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Validation of agent messages, following the proto comments ("Empty is invalid…"). */
final class AgentMessages {

    /** The uuid prefix of the agent's invented GPUs (`--fake-gpu`), for development only. */
    static final String FAKE_GPU_PREFIX = "GPU-00000000-fake";

    private AgentMessages() {}

    /** A Hello that passed validation, ready to record. */
    record ValidHello(AgentHello hello, SemanticVersion version, Set<String> gpuUuids) {}

    /** The validated Hello, or the reason it's invalid. */
    sealed interface HelloResult {
        record Valid(ValidHello hello) implements HelloResult {}

        record Invalid(String reason) implements HelloResult {}
    }

    /**
     * Validates a Hello. Unless {@code allowDevelopmentHosts}, a Mac or a fake GPU is refused: neither can
     * ever be rented out.
     */
    static HelloResult validate(Hello hello, boolean allowDevelopmentHosts) {
        var version = SemanticVersion.parse(hello.getAgentVersion());
        if (version.isEmpty()) {
            return new HelloResult.Invalid("agent_version must be a semantic version");
        }
        var os = osName(hello.getOs());
        if (os == null) {
            return new HelloResult.Invalid("os must be set");
        }
        if (hello.getOs() == OperatingSystem.OPERATING_SYSTEM_MACOS && !allowDevelopmentHosts) {
            return new HelloResult.Invalid("macOS hosts are for development only");
        }
        if (hello.getGpusCount() == 0) {
            return new HelloResult.Invalid("at least one GPU is required");
        }
        var uuids = new HashSet<String>();
        var gpus = new ArrayList<ReportedGpu>();
        for (var gpu : hello.getGpusList()) {
            if (gpu.getUuid().isBlank()) {
                return new HelloResult.Invalid("every GPU needs a uuid");
            }
            if (gpu.getVramMb() <= 0) {
                return new HelloResult.Invalid("every GPU needs vram_mb above zero");
            }
            if (!uuids.add(gpu.getUuid())) {
                return new HelloResult.Invalid("a GPU uuid appears twice");
            }
            if (gpu.getUuid().startsWith(FAKE_GPU_PREFIX) && !allowDevelopmentHosts) {
                return new HelloResult.Invalid("fake GPUs are for development only");
            }
            gpus.add(new ReportedGpu(
                    gpu.getUuid(),
                    gpu.getName(),
                    gpu.getVramMb(),
                    blankToNull(gpu.getDriverVersion()),
                    blankToNull(gpu.getCudaDriverVersion()),
                    blankToNull(gpu.getComputeCapability())));
        }
        return new HelloResult.Valid(
                new ValidHello(new AgentHello(os, hello.getAgentVersion(), gpus), version.get(), Set.copyOf(uuids)));
    }

    /** Why a heartbeat is invalid, or empty if it is valid for a host with these GPUs. */
    static Optional<String> invalidHeartbeat(Heartbeat heartbeat, Set<String> reportedGpus) {
        if (heartbeat.getNetUpKbps() < 0) {
            return Optional.of("net_up_kbps_out_of_range");
        }
        for (GpuTelemetry gpu : heartbeat.getGpusList()) {
            if (!reportedGpus.contains(gpu.getUuid())) {
                return Optional.of("unknown_gpu");
            }
            if (gpu.getTempC() < 0 || gpu.getTempC() > 150) {
                return Optional.of("temperature_out_of_range");
            }
            if (gpu.getUtilPct() < 0 || gpu.getUtilPct() > 100) {
                return Optional.of("utilisation_out_of_range");
            }
            if (gpu.getVramUsedMb() < 0) {
                return Optional.of("vram_out_of_range");
            }
            if (gpu.hasPowerW() && gpu.getPowerW() < 0) {
                return Optional.of("power_out_of_range");
            }
        }
        return Optional.empty();
    }

    /** Why a SessionStatus breaks the protocol, or empty if it's well formed. */
    static Optional<String> invalidSessionStatus(SessionStatus status) {
        try {
            java.util.UUID.fromString(status.getSessionId());
        } catch (IllegalArgumentException e) {
            return Optional.of("session_id must be a UUID");
        }
        if (status.getAttempt() <= 0) {
            return Optional.of("attempt must be above zero");
        }
        if (status.getState() == SessionState.SESSION_STATE_UNSPECIFIED
                || status.getState() == SessionState.UNRECOGNIZED) {
            return Optional.of("state must be set");
        }
        if (status.getLatestCheckpointSeq() < 0) {
            return Optional.of("latest_checkpoint_seq must not be negative");
        }
        return Optional.empty();
    }

    private static @Nullable String osName(OperatingSystem os) {
        return switch (os) {
            case OPERATING_SYSTEM_WINDOWS, OPERATING_SYSTEM_LINUX, OPERATING_SYSTEM_MACOS ->
                os.name().substring("OPERATING_SYSTEM_".length()).toLowerCase(Locale.ROOT);
            default -> null;
        };
    }

    private static @Nullable String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
