package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.hosts.HostAgentReports.AgentHello;
import com.idleo.controlplane.hosts.HostAgentReports.ReportedGpu;
import gpu.agent.v1.AgentGateway.Hello;
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

    private AgentMessages() {}

    /** A Hello that passed validation, ready to record. */
    record ValidHello(AgentHello hello, SemanticVersion version, Set<String> gpuUuids) {}

    /** The validated Hello, or the reason it's invalid. */
    sealed interface HelloResult {
        record Valid(ValidHello hello) implements HelloResult {}

        record Invalid(String reason) implements HelloResult {}
    }

    static HelloResult validate(Hello hello) {
        var version = SemanticVersion.parse(hello.getAgentVersion());
        if (version.isEmpty()) {
            return new HelloResult.Invalid("agent_version must be a semantic version");
        }
        var os = osName(hello.getOs());
        if (os == null) {
            return new HelloResult.Invalid("os must be set");
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

    private static @Nullable String osName(OperatingSystem os) {
        return switch (os) {
            case OPERATING_SYSTEM_WINDOWS, OPERATING_SYSTEM_LINUX ->
                os.name().substring("OPERATING_SYSTEM_".length()).toLowerCase(Locale.ROOT);
            default -> null;
        };
    }

    private static @Nullable String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
