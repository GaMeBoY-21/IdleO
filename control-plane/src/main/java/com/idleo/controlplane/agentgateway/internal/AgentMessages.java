package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.SemanticVersion;
import com.idleo.controlplane.hosts.HostAgentReports.AgentHello;
import com.idleo.controlplane.hosts.HostAgentReports.ReportedGpu;
import com.idleo.controlplane.hosts.HostAgentReports.SelfTest;
import com.idleo.controlplane.hosts.HostAgentReports.SelfTestCheck;
import com.idleo.controlplane.hosts.HostAgentReports.UpdateOutcome;
import gpu.agent.v1.AgentGateway.Hello;
import gpu.agent.v1.Host.SelfTestReport;
import gpu.agent.v1.Host.UpdateResult;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Session.UsageReport;
import gpu.agent.v1.Telemetry.GpuTelemetry;
import gpu.agent.v1.Telemetry.Heartbeat;
import gpu.agent.v1.Telemetry.OperatingSystem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
        if (hello.hasFreeDiskMb() && hello.getFreeDiskMb() < 0) {
            return new HelloResult.Invalid("free_disk_mb can't be below zero");
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
        if (heartbeat.hasFreeDiskMb() && heartbeat.getFreeDiskMb() < 0) {
            return Optional.of("free_disk_out_of_range");
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
        for (var session : heartbeat.getSessionsList()) {
            if (!isUuid(session.getSessionId()) || session.getAttempt() < 1) {
                return Optional.of("invalid_running_session");
            }
            if (session.getLatestLogSeq() < 0) {
                return Optional.of("log_seq_out_of_range");
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
        if (status.getLatestLogSeq() < 0) {
            return Optional.of("latest_log_seq must not be negative");
        }
        return Optional.empty();
    }

    /**
     * Why a UsageReport can't even name its minute, or empty. Values out of range are the metering
     * module's to judge: it acks and drops them with a counted reason.
     */
    static Optional<String> invalidUsageReport(UsageReport report) {
        try {
            java.util.UUID.fromString(report.getSessionId());
        } catch (IllegalArgumentException e) {
            return Optional.of("session_id must be a UUID");
        }
        if (report.getAttempt() <= 0) {
            return Optional.of("attempt must be above zero");
        }
        if (!report.hasMinuteIndex()) {
            return Optional.of("minute_index must be set");
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

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return value.length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The most checks one self-test may carry, and the longest detail kept for each. */
    static final int MAX_SELF_TEST_CHECKS = 32;

    static final int MAX_DETAIL = 300;
    /** The fastest upload a self-test may report, in kbit/s: 100 Gbit/s. */
    static final int MAX_KBPS = 100_000_000;

    /** A self-test ready to record, or the reason it's invalid. */
    sealed interface SelfTestResult {
        record Valid(SelfTest selfTest) implements SelfTestResult {}

        record Invalid(String reason) implements SelfTestResult {}
    }

    /** Validates a SelfTestReport: at most 32 checks, each named; long details are cut, never refused. */
    static SelfTestResult validate(SelfTestReport report) {
        if (report.getChecksCount() > MAX_SELF_TEST_CHECKS) {
            return new SelfTestResult.Invalid("more than " + MAX_SELF_TEST_CHECKS + " checks");
        }
        var checks = new ArrayList<SelfTestCheck>();
        for (var check : report.getChecksList()) {
            if (check.getName().isBlank() || check.getName().length() > 60) {
                return new SelfTestResult.Invalid("every check needs a name of at most 60 characters");
            }
            long tookMs = check.hasTook()
                    ? Math.max(
                            0,
                            check.getTook().getSeconds() * 1000
                                    + check.getTook().getNanos() / 1_000_000)
                    : 0;
            checks.add(new SelfTestCheck(check.getName(), check.getPassed(), cut(check.getDetail()), tookMs));
        }
        if (report.getPassed() && checks.stream().anyMatch(check -> !check.passed())) {
            return new SelfTestResult.Invalid("passed is true but a check failed");
        }
        if (report.getUploadKbps() > MAX_KBPS
                || report.getUploadCapKbps() > MAX_KBPS
                || report.getUploadKbps() < 0
                || report.getUploadCapKbps() < 0) {
            return new SelfTestResult.Invalid("an upload speed over " + MAX_KBPS + " kbit/s");
        }
        return new SelfTestResult.Valid(new SelfTest(
                report.getPassed(),
                cut(report.getDistroVersion()),
                checks,
                report.getUploadKbps(),
                report.getUploadCapKbps()));
    }

    /** The outcome of a valid UpdateResult, or empty if it's invalid. */
    static Optional<UpdateOutcome> outcome(UpdateResult result) {
        if (SemanticVersion.parse(result.getVersion()).isEmpty()) {
            return Optional.empty();
        }
        return switch (result.getOutcome()) {
            case OUTCOME_INSTALLED -> Optional.of(UpdateOutcome.INSTALLED);
            case OUTCOME_ROLLED_BACK -> Optional.of(UpdateOutcome.ROLLED_BACK);
            case OUTCOME_FAILED_VERIFY -> Optional.of(UpdateOutcome.FAILED_VERIFY);
            case OUTCOME_FAILED_DOWNLOAD -> Optional.of(UpdateOutcome.FAILED_DOWNLOAD);
            default -> Optional.empty();
        };
    }

    static String cut(String text) {
        var strip = text.strip();
        return strip.length() <= MAX_DETAIL ? strip : strip.substring(0, MAX_DETAIL);
    }
}
