package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.hosts.HostAgentReports;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Everything an {@link AgentStream} works with, in one place. */
@Component
class AgentStreamServices implements DisposableBean {

    private final HostAgentReports reports;
    private final Presence presence;
    private final ConnectedAgents connected;
    private final AgentGatewayMetrics metrics;
    private final AgentGatewayProperties properties;
    private final SemanticVersion minimumAgentVersion;
    private final IdGenerator ids;
    private final InstanceId instanceId;
    private final JsonMapper json;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "agent-hello-timer");
        thread.setDaemon(true);
        return thread;
    });

    AgentStreamServices(
            HostAgentReports reports,
            Presence presence,
            ConnectedAgents connected,
            AgentGatewayMetrics metrics,
            AgentGatewayProperties properties,
            IdGenerator ids,
            InstanceId instanceId,
            JsonMapper json,
            Clock clock) {
        this.reports = reports;
        this.presence = presence;
        this.connected = connected;
        this.metrics = metrics;
        this.properties = properties;
        this.minimumAgentVersion = SemanticVersion.parse(properties.minimumAgentVersion())
                .orElseThrow(() -> new IllegalStateException("agentgateway.minimum-agent-version is not semver"));
        this.ids = ids;
        this.instanceId = instanceId;
        this.json = json;
        this.clock = clock;
    }

    HostAgentReports reports() {
        return reports;
    }

    Presence presence() {
        return presence;
    }

    ConnectedAgents connected() {
        return connected;
    }

    AgentGatewayMetrics metrics() {
        return metrics;
    }

    AgentGatewayProperties properties() {
        return properties;
    }

    SemanticVersion minimumAgentVersion() {
        return minimumAgentVersion;
    }

    IdGenerator ids() {
        return ids;
    }

    InstanceId instanceId() {
        return instanceId;
    }

    JsonMapper json() {
        return json;
    }

    Clock clock() {
        return clock;
    }

    ScheduledExecutorService scheduler() {
        return scheduler;
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
