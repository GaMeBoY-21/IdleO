package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostAgentRequests;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * A host's agent acting for its owner (see {@link HostAgentRequests}): every request goes through {@link
 * HostLifecycle}, the same code the web app's Hosting tab calls, as the host's owner, so a tray pause and a web pause
 * are one pause.
 */
@Service
class HostAgentRequestService implements HostAgentRequests {

    private final HostLifecycle lifecycle;
    private final HostRepository hosts;
    private final Clock clock;

    HostAgentRequestService(HostLifecycle lifecycle, HostRepository hosts, Clock clock) {
        this.lifecycle = lifecycle;
        this.hosts = hosts;
        this.clock = clock;
    }

    @Override
    public void pause(UUID hostId, @Nullable Instant until) {
        lifecycle.pause(owner(hostId), hostId, until);
    }

    @Override
    public void resume(UUID hostId) {
        lifecycle.resume(owner(hostId), hostId);
    }

    @Override
    public void leave(UUID hostId) {
        try {
            lifecycle.remove(owner(hostId), hostId);
        } catch (ApiProblem problem) {
            if (problem.problem() != Problem.HOST_REMOVED) {
                throw problem;
            }
        }
    }

    @Override
    public Optional<Standing> standing(UUID hostId) {
        var now = Instant.now(clock);
        return hosts.findById(hostId).map(host -> {
            var status = HostStatus.valueOf(host.status().toUpperCase(java.util.Locale.ROOT));
            return switch (status) {
                case REMOVED -> new Standing("removed", null);
                case SUSPENDED -> new Standing("suspended", null);
                case PENDING, VERIFIED -> new Standing("pending", null);
                case ACTIVE ->
                    host.paused(now) ? new Standing("paused", host.pausedUntil()) : new Standing("active", null);
            };
        });
    }

    private UUID owner(UUID hostId) {
        return hosts.findById(hostId)
                .map(Host::ownerUserId)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such host."));
    }
}
