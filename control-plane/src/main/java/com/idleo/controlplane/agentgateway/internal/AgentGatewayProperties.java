package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.SemanticVersion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The agent stream's settings (ADR 0011).
 *
 * @param helloTimeout how long a new stream may wait before its first message, which must be Hello
 * @param presenceTtl how long presence lives after the last heartbeat (technical design line 352)
 * @param heartbeatInterval how often agents send Heartbeat; sent in Welcome
 * @param gpuTempCapC the GPU temperature cap sent in Welcome, until host listings set one per host
 * @param maxConcurrentSessions the session limit sent in Welcome, until host listings set one per host
 * @param minimumAgentVersion older agents are welcomed but marked update_required in presence
 * @param allowDevelopmentHosts accept a Hello from a Mac or with fake GPUs; on only in the local and
 *     test profiles, and the application refuses to start with it in prod (ADR 0013)
 * @param commandSweepInterval how often queued commands for connected agents are swept, in case a
 *     delivery hint was lost (ADR 0015)
 * @param commandResendAfter a command needing an Ack is resent after this long without one
 */
@ConfigurationProperties("agentgateway")
@Validated
record AgentGatewayProperties(
        @NotNull Duration helloTimeout,
        @NotNull Duration presenceTtl,
        @NotNull Duration heartbeatInterval,
        @Positive int gpuTempCapC,
        @Positive int maxConcurrentSessions,
        @NotNull @Pattern(regexp = SemanticVersion.PATTERN) String minimumAgentVersion,
        boolean allowDevelopmentHosts,
        @NotNull Duration commandSweepInterval,
        @NotNull Duration commandResendAfter) {}
