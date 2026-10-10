package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostListings;
import com.idleo.controlplane.hosts.HostPresence;
import com.idleo.controlplane.hosts.HostWork;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** A host as its owner sees it, built once for the list, one host and every change to a host. */
@Component
class HostViews {

    record GpuResponse(String model, int vramMb, boolean gone) {}

    /**
     * A host as its owner sees it. {@code online} is whether its agent has a live stream now; {@code
     * lastSeenAt} is when the agent was last seen, to the minute. {@code paused} is true from the owner's pause until
     * it ends or they resume; {@code pausedUntil} is empty for a pause with no end. {@code agentVersion} is what
     * the agent last reported; {@code updateRequired} says it's older than the oldest release we support, and {@code
     * onlineSince} when its current stream opened, empty while it's offline. {@code currentSession} is what it's
     * running now and what it has earned from that so far, never for whom; empty when it's idle.
     */
    record HostResponse(
            UUID id,
            String name,
            String status,
            boolean online,
            @Nullable Instant lastSeenAt,
            boolean paused,
            @Nullable Instant pausedUntil,
            @Nullable String agentVersion,
            boolean updateRequired,
            @Nullable Instant onlineSince,
            HostWork.@Nullable CurrentSession currentSession,
            List<GpuResponse> gpus,
            HostListings.@Nullable Listing listing,
            Instant createdAt) {}

    private final HostService hosts;
    private final HostListingService listings;
    private final HostPresence presence;
    private final HostWork work;
    private final Clock clock;

    HostViews(HostService hosts, HostListingService listings, HostPresence presence, HostWork work, Clock clock) {
        this.hosts = hosts;
        this.listings = listings;
        this.presence = presence;
        this.work = work;
        this.clock = clock;
    }

    /** The owner's host, or not-found if it isn't theirs, or host-removed if they removed it. */
    Host owned(UUID owner, UUID hostId) {
        var host = hosts.find(owner, hostId).orElseThrow(HostViews::notFound);
        if (host.removed()) {
            throw ApiProblem.of(Problem.HOST_REMOVED, "This host was removed.");
        }
        return host;
    }

    HostResponse view(Host host) {
        return views(List.of(host)).getFirst();
    }

    /** One presence lookup and one GPU query for the whole list. */
    List<HostResponse> views(Collection<Host> list) {
        var ids = list.stream().map(Host::id).toList();
        var connections = ids.isEmpty() ? Map.<UUID, HostPresence.Connection>of() : presence.connections(ids);
        var gpus = hosts.gpus(ids);
        var current = work.current(ids);
        var now = Instant.now(clock);
        return list.stream()
                .map(host -> {
                    boolean paused = host.paused(now);
                    var connection = connections.get(host.id());
                    return new HostResponse(
                            host.id(),
                            host.name(),
                            host.status(),
                            connection != null,
                            host.lastSeenAt(),
                            paused,
                            paused ? host.pausedUntil() : null,
                            host.agentVersion(),
                            connection != null && connection.updateRequired(),
                            connection == null ? null : connection.since(),
                            current.get(host.id()),
                            gpus.getOrDefault(host.id(), List.of()).stream()
                                    .map(gpu -> new GpuResponse(gpu.model(), gpu.vramMb(), gpu.gone()))
                                    .toList(),
                            listings.listing(host.id()).orElse(null),
                            host.createdAt());
                })
                .toList();
    }

    static ApiProblem notFound() {
        return ApiProblem.of(Problem.NOT_FOUND, "No such host.");
    }
}
