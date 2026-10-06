package com.idleo.controlplane.agentgateway.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The agent gateway's meters (ADR 0011). Technical design line 798 alerts on hosts online; this is
 * the data it reads.
 */
@Component
class AgentGatewayMetrics {

    private final MeterRegistry registry;

    AgentGatewayMetrics(MeterRegistry registry, ConnectedAgents connected) {
        this.registry = registry;
        Gauge.builder("agents.connected", connected, ConnectedAgents::size)
                .description("Agent streams open on this instance, after Hello")
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
