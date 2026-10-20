package com.idleo.controlplane.agentgateway.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The agent gateway's meters (ADR 0011, ADR 0036): streams open on this instance ({@code agents.connected}, which the
 * agents-dropped alert reads), how long the quietest has gone without a heartbeat, and why streams close or Hellos are
 * refused.
 */
@Component
class AgentGatewayMetrics {

    private final MeterRegistry registry;

    AgentGatewayMetrics(MeterRegistry registry, ConnectedAgents connected, Clock clock) {
        this.registry = registry;
        Gauge.builder("agents.connected", connected, ConnectedAgents::size)
                .description("Agent streams open on this instance, after Hello")
                .register(registry);
        Gauge.builder(
                        "agents.heartbeat.age.max",
                        connected,
                        agents -> agents.longestSilence(Instant.now(clock)).toMillis() / 1000.0)
                .description("The longest any open stream on this instance has gone without a heartbeat")
                .baseUnit("seconds")
                .register(registry);
    }

    void connectRefused(String reason) {
        registry.counter("agents.connect.refused", "reason", reason).increment();
    }

    void heartbeat(String outcome) {
        registry.counter("agents.heartbeats", "outcome", outcome).increment();
    }

    void invalidHeartbeat(String reason) {
        registry.counter("agents.heartbeats.invalid", "reason", reason).increment();
    }

    void streamClosed(String reason) {
        registry.counter("agents.streams.closed", "reason", reason).increment();
    }

    /** A host page asked whether hosts are online and Valkey couldn't answer; they showed as offline. */
    void presenceUnavailable() {
        registry.counter("agents.presence.unavailable").increment();
    }

    void unhandledMessage(String type) {
        registry.counter("agents.messages.unhandled", "type", type).increment();
    }
}
