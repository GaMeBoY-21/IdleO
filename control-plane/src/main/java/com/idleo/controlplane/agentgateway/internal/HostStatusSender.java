package com.idleo.controlplane.agentgateway.internal;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.idleo.controlplane.agentgateway.AgentCommands;
import com.idleo.controlplane.billing.HostEarnings;
import com.idleo.controlplane.billing.HostEarningsChanged;
import com.idleo.controlplane.hosts.AgentReleaseCached;
import com.idleo.controlplane.hosts.AgentReleases;
import com.idleo.controlplane.hosts.HostAgentRequests;
import com.idleo.controlplane.hosts.HostStandingChanged;
import com.idleo.controlplane.hosts.HostWork;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.AgentGateway.UpdateAgent;
import gpu.agent.v1.Host.HostState;
import gpu.agent.v1.Host.HostStatus;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the control plane tells a host's agent about itself (ADR 0034), both through the durable command queue, each
 * replacing the last undelivered one:
 *
 * <ul>
 *   <li>{@code HostStatus}, for its tray: after Welcome, whenever the host's status or pause changes ({@link
 *       HostStandingChanged}), and whenever its earnings change ({@link HostEarningsChanged}); today's earnings are
 *       billing's own figure, from the ledger.
 *   <li>{@code UpdateAgent}, once per connection after Welcome and whenever this instance caches a newer release
 *       ({@link AgentReleaseCached}), only when the hosts module says the host should have it.
 * </ul>
 */
@Component
class HostStatusSender {

    private static final Logger log = LoggerFactory.getLogger(HostStatusSender.class);
    static final String HOST_STATUS = "host_status";
    static final String UPDATE_AGENT = "update_agent";

    private final HostAgentRequests hosts;
    private final HostEarnings earnings;
    private final HostWork work;
    private final AgentReleases releases;
    private final AgentCommands commands;
    private final ConnectedAgents connected;
    private final TransactionTemplate transactions;

    HostStatusSender(
            HostAgentRequests hosts,
            HostEarnings earnings,
            HostWork work,
            AgentReleases releases,
            AgentCommands commands,
            ConnectedAgents connected,
            PlatformTransactionManager transactionManager) {
        this.hosts = hosts;
        this.earnings = earnings;
        this.work = work;
        this.releases = releases;
        this.commands = commands;
        this.connected = connected;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Right after Welcome, before anything queued is delivered: where the host stands, and any update for it. */
    void welcomed(UUID hostId, String agentVersion) {
        transactions.executeWithoutResult(status -> {
            queueStatus(hostId);
            queueUpdate(hostId, agentVersion);
        });
    }

    /** The host rolled a version back: an update it hasn't received yet isn't sent any more. */
    void rolledBack(UUID hostId, String agentVersion) {
        transactions.executeWithoutResult(status -> queueUpdate(hostId, agentVersion));
    }

    /** Where the host stands now, queued for its agent. */
    void sendStatus(UUID hostId) {
        transactions.executeWithoutResult(status -> queueStatus(hostId));
    }

    @ApplicationModuleListener
    void on(HostStandingChanged event) {
        queueStatus(event.hostId());
    }

    @ApplicationModuleListener
    void on(HostEarningsChanged event) {
        event.hostIds().forEach(this::queueStatus);
    }

    /** A new manifest on this instance: offered to (or withdrawn from) the agents whose streams it holds. */
    @EventListener
    void on(AgentReleaseCached event) {
        for (var hostId : connected.hostIds()) {
            var stream = connected.get(hostId);
            if (stream == null) {
                continue;
            }
            try {
                transactions.executeWithoutResult(status -> queueUpdate(hostId, stream.agentVersion()));
            } catch (RuntimeException e) {
                log.warn("Couldn't offer release {} to host {}; its next connection will", event.version(), hostId, e);
            }
        }
    }

    private void queueStatus(UUID hostId) {
        var standing = hosts.standing(hostId);
        if (standing.isEmpty()) {
            return;
        }
        var message = HostStatus.newBuilder()
                .setState(state(standing.get().state()))
                .setEarnedTodayPaise(earnings.earnedToday(hostId));
        var running = work.current(List.of(hostId)).get(hostId);
        if (running != null) {
            message.setEarningNowPaise(running.earnedSoFarPaise());
        }
        var until = standing.get().pausedUntil();
        if (until != null) {
            message.setPausedUntil(
                    Timestamp.newBuilder().setSeconds(until.getEpochSecond()).setNanos(until.getNano()));
        }
        commands.replace(hostId, HOST_STATUS, OpenStreamResponse.newBuilder().setHostStatus(message));
    }

    /**
     * Queues the update the host should have, in place of any earlier one, or withdraws an undelivered one it should no
     * longer get (its rollout lowered to stop a release, or rolled back there).
     */
    void queueUpdate(UUID hostId, String agentVersion) {
        var offered = releases.updateFor(hostId, agentVersion);
        if (offered.isEmpty()) {
            commands.withdrawFor(hostId, UPDATE_AGENT);
        }
        offered.ifPresent(update -> commands.replace(
                hostId,
                UPDATE_AGENT,
                OpenStreamResponse.newBuilder()
                        .setUpdateAgent(UpdateAgent.newBuilder()
                                .setVersion(update.version())
                                .setManifest(ByteString.copyFrom(update.manifest()))
                                .setManifestSignature(ByteString.copyFrom(update.signature()))
                                .setAppUrl(update.appUrl())
                                .setDistroUrl(update.distroUrl()))));
    }

    static HostState state(String standing) {
        return switch (standing) {
            case "pending" -> HostState.HOST_STATE_PENDING_APPROVAL;
            case "active" -> HostState.HOST_STATE_ACTIVE;
            case "paused" -> HostState.HOST_STATE_PAUSED;
            case "removed" -> HostState.HOST_STATE_REMOVED;
            case "suspended" -> HostState.HOST_STATE_SUSPENDED;
            default -> throw new IllegalArgumentException("unknown host standing " + standing);
        };
    }
}
