package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.agentgateway.OfferReplies;
import com.idleo.controlplane.agentgateway.SessionLogProgress;
import com.idleo.controlplane.agentgateway.SessionReconciliation;
import com.idleo.controlplane.agentgateway.SessionStatuses;
import com.idleo.controlplane.agentgateway.UsageReports;
import com.idleo.controlplane.hosts.HostAgentReports;
import com.idleo.controlplane.hosts.HostListings;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Everything an {@link AgentStream} works with, in one place. */
@Component
class AgentStreamServices implements DisposableBean {

    private final HostAgentReports reports;
    private final HostListings listings;
    private final AgentCommandService commands;
    private final ObjectProvider<OfferReplies> offerReplies;
    private final ObjectProvider<SessionStatuses> sessionStatuses;
    private final ObjectProvider<SessionLogProgress> logProgress;
    private final ObjectProvider<SessionReconciliation> reconciliation;
    private final ObjectProvider<UsageReports> usageReports;
    private final ObjectProvider<CommandDelivery> delivery;
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
            HostListings listings,
            AgentCommandService commands,
            ObjectProvider<OfferReplies> offerReplies,
            ObjectProvider<SessionStatuses> sessionStatuses,
            ObjectProvider<SessionLogProgress> logProgress,
            ObjectProvider<SessionReconciliation> reconciliation,
            ObjectProvider<UsageReports> usageReports,
            ObjectProvider<CommandDelivery> delivery,
            Presence presence,
            ConnectedAgents connected,
            AgentGatewayMetrics metrics,
            AgentGatewayProperties properties,
            IdGenerator ids,
            InstanceId instanceId,
            JsonMapper json,
            Clock clock) {
        this.reports = reports;
        this.listings = listings;
        this.commands = commands;
        this.offerReplies = offerReplies;
        this.sessionStatuses = sessionStatuses;
        this.logProgress = logProgress;
        this.reconciliation = reconciliation;
        this.usageReports = usageReports;
        this.delivery = delivery;
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

    HostListings listings() {
        return listings;
    }

    AgentCommandService commands() {
        return commands;
    }

    @Nullable
    OfferReplies offerReplies() {
        return offerReplies.getIfAvailable();
    }

    @Nullable
    SessionLogProgress logProgress() {
        return logProgress.getIfAvailable();
    }

    @Nullable
    SessionStatuses sessionStatuses() {
        return sessionStatuses.getIfAvailable();
    }

    @Nullable
    SessionReconciliation reconciliation() {
        return reconciliation.getIfAvailable();
    }

    @Nullable
    UsageReports usageReports() {
        return usageReports.getIfAvailable();
    }

    @Nullable
    CommandDelivery delivery() {
        return delivery.getIfAvailable();
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
