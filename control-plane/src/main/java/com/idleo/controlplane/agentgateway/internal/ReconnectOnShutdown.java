package com.idleo.controlplane.agentgateway.internal;

import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Tells every agent connected to this instance to reconnect as it stops (a rolling update, ADR 0045), before the gRPC
 * server begins its own graceful shutdown. Agent streams never end on their own, so without this the server would wait
 * out its whole grace period and then cut them. {@code UNAVAILABLE} is the agent's ordinary retry: it reconnects after
 * a jittered delay (agent/src/backoff.rs), and the load balancer sends it to an instance that's still serving. The
 * agent's lease outlives the gap, so its session runs on.
 *
 * <p>The context publishes this event before it stops any lifecycle, the gRPC server's included.
 */
@Component
class ReconnectOnShutdown implements ApplicationListener<ContextClosedEvent> {

    static final Status RESTARTING = Status.UNAVAILABLE.withDescription("This control plane is stopping; reconnect.");

    private static final Logger log = LoggerFactory.getLogger(ReconnectOnShutdown.class);

    private final ConnectedAgents connected;

    ReconnectOnShutdown(ConnectedAgents connected) {
        this.connected = connected;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        var hosts = connected.hostIds();
        for (var hostId : hosts) {
            var stream = connected.get(hostId);
            if (stream != null) {
                stream.close(RESTARTING, "shutdown");
            }
        }
        if (!hosts.isEmpty()) {
            log.info("Told {} connected agents to reconnect elsewhere", hosts.size());
        }
    }
}
