package com.idleo.controlplane.agentgateway.internal;

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
 * @param presenceTtl how long presence lives after the last heartbeat (technical design line 330)
 * @param heartbeatInterval how often agents send Heartbeat; sent in Welcome
 * @param gpuTempCapC the GPU temperature cap sent in Welcome, until host listings set one per host
 * @param maxConcurrentSessions the session limit sent in Welcome, until host listings set one per host
 * @param minimumAgentVersion older agents are welcomed but marked update_required in presence
 */
@ConfigurationProperties("agentgateway")
@Validated
record AgentGatewayProperties(
        @NotNull Duration helloTimeout,
        @NotNull Duration presenceTtl,
        @NotNull Duration heartbeatInterval,
        @Positive int gpuTempCapC,
        @Positive int maxConcurrentSessions,
        @NotNull @Pattern(regexp = SemanticVersion.PATTERN) String minimumAgentVersion) {}
