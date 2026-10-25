package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostListings;
import com.idleo.controlplane.hosts.HostPresence;
import com.idleo.controlplane.hosts.HostRatings;
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

    /** One thing that lowers a machine's reliability: {@code kind} and, where it's a count, how many. */
    record Lowers(String kind, @Nullable Integer count) {}

    /**
     * How reliable the machine has been, in a word for its owner: {@code label} is good, fair or new (little to go
     * on yet), {@code lowers} what is holding it down. Never says which renter reported, or that an admin marked it.
     */
    record Reliability(String label, List<Lowers> lowers) {}

    /**
     * A host as its owner sees it. {@code online} is whether its agent has a live stream now; {@code
     * lastSeenAt} is when the agent was last seen, to the minute. {@code paused} is true from the owner's pause until
     * it ends or they resume; {@code pausedUntil} is empty for a pause with no end. {@code agentVersion} is what
     * the agent last reported; {@code updateRequired} says it's older than the oldest release we support, and {@code
     * onlineSince} when its current stream opened, empty while it's offline. {@code currentSession} is what it's
     * running now and what it has earned from that so far, never for whom; empty when it's idle. {@code rating} is
     * its renters' average over the last 90 days, empty until 5 of them have rated it (ADR 0028). {@code
     * updateWaiting} says a newer release of the Windows app is offered to it, which it installs once it's idle (ADR
     * 0034). {@code uploadCapKbps} is the most a job may upload at once on it, in kbit/s: half of the upload its agent
     * measured, never under its floor (ADR 0038); empty until the first speed check.
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
            boolean updateWaiting,
            @Nullable Instant onlineSince,
            HostWork.@Nullable CurrentSession currentSession,
            List<GpuResponse> gpus,
            HostListings.@Nullable Listing listing,
            HostRatings.@Nullable Rating rating,
            @Nullable Integer uploadCapKbps,
            Reliability reliability,
            Instant createdAt) {}

    private final HostService hosts;
    private final HostListingService listings;
    private final HostPresence presence;
    private final HostWork work;
    private final HostRatings ratings;
    private final AgentReleaseFeed releases;
    private final HostScoreService scores;
    private final Clock clock;

    HostViews(
            HostService hosts,
            HostListingService listings,
            HostPresence presence,
            HostWork work,
            HostRatings ratings,
            AgentReleaseFeed releases,
            HostScoreService scores,
            Clock clock) {
        this.scores = scores;
        this.hosts = hosts;
        this.listings = listings;
        this.presence = presence;
        this.work = work;
        this.ratings = ratings;
        this.releases = releases;
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
        var rated = ids.isEmpty() ? Map.<UUID, HostRatings.Rating>of() : ratings.of(ids);
        var caps = hosts.uploadCaps(ids);
        var scored = scores.stored(ids);
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
                            !host.removed()
                                    && releases.eligible(host.id(), host.agentVersion())
                                            .isPresent(),
                            connection == null ? null : connection.since(),
                            current.get(host.id()),
                            gpus.getOrDefault(host.id(), List.of()).stream()
                                    .map(gpu -> new GpuResponse(gpu.model(), gpu.vramMb(), gpu.gone()))
                                    .toList(),
                            listings.listing(host.id()).orElse(null),
                            rated.get(host.id()),
                            caps.get(host.id()),
                            reliability(scored.get(host.id())),
                            host.createdAt());
                })
                .toList();
    }

    /** A machine with no score yet is new, with nothing holding it down. */
    static Reliability reliability(HostScoreService.@Nullable Stored stored) {
        if (stored == null) {
            return new Reliability("new", List.of());
        }
        var b = stored.breakdown();
        var lowers = new java.util.ArrayList<Lowers>();
        if (b.sessionsLost() > 0) {
            lowers.add(new Lowers("sessions_lost", b.sessionsLost()));
        }
        if (b.selfTestsFailed() > 0) {
            lowers.add(new Lowers("self_tests_failed", b.selfTestsFailed()));
        }
        if (b.reportsUpheld() + b.flags() > 0) {
            lowers.add(new Lowers("reports", null));
        }
        return new Reliability(b.label().name().toLowerCase(java.util.Locale.ROOT), List.copyOf(lowers));
    }

    static ApiProblem notFound() {
        return ApiProblem.of(Problem.NOT_FOUND, "No such host.");
    }
}
