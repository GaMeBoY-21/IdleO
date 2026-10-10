package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.hosts.HostTelemetry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Answers the hosts module's {@link HostTelemetry} port from presence and the telemetry ring. */
@Component
class HostTelemetryService implements HostTelemetry {

    private static final Logger log = LoggerFactory.getLogger(HostTelemetryService.class);

    private final Presence presence;
    private final TelemetryRing ring;
    private final AgentGatewayMetrics metrics;
    private final Clock clock;

    HostTelemetryService(Presence presence, TelemetryRing ring, AgentGatewayMetrics metrics, Clock clock) {
        this.presence = presence;
        this.ring = ring;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public Telemetry of(UUID hostId) {
        Point now = null;
        var agent = presence.live(List.of(hostId)).get(hostId);
        if (agent != null && !agent.gpus().isEmpty()) {
            now = new Point(
                    Instant.now(clock),
                    agent.gpus().stream()
                            .map(gpu -> new Reading(gpu.gpuUuid(), gpu.tempC(), gpu.utilPct(), gpu.vramUsedMb()))
                            .toList());
        }
        try {
            return new Telemetry(now, ring.points(hostId));
        } catch (RuntimeException valkeyDown) {
            metrics.presenceUnavailable();
            log.debug("Telemetry history unavailable for host {}", hostId, valkeyDown);
            return new Telemetry(now, List.of());
        }
    }
}
