package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.CudaDrivers;
import com.idleo.controlplane.agentgateway.AgentPresence;
import com.idleo.controlplane.hosts.SchedulableHosts;
import com.idleo.controlplane.sessions.SessionBookings;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Hosts that could take a session, each fact from the module that owns it:
 * active, listed, not paused and big enough (hosts); online, current, owner away, every GPU below the cap, and room
 * on its disk for the session's files once the owner's share is kept free (agent gateway presence, ADRs 0022, 0023);
 * no live session (sessions); not already offered this session; and, for the renter's own image, a GPU whose driver runs
 * the CUDA version it needs ({@link CudaDrivers}, the same rule as the agent's {@code MIN_NVIDIA_DRIVER}, ADR 0042).
 *
 * <p>These are the scheduler's hard filters, in one place: matching, quotes and availability all use {@link #look}
 * (ADR 0019), so a quote that says a host fits never disagrees with the booking that follows.
 */
@Component
class CandidateFinder {

    private final SchedulableHosts hosts;
    private final AgentPresence presence;
    private final SessionBookings sessions;

    CandidateFinder(SchedulableHosts hosts, AgentPresence presence, SessionBookings sessions) {
        this.hosts = hosts;
        this.presence = presence;
        this.sessions = sessions;
    }

    /**
     * A listed host as the scheduler sees it now.
     *
     * @param online its agent's presence is live and it isn't paused: a paused host is neither offered sessions nor
     *     counted in availability or its price range
     * @param free it could take a session now: not paused, online, current, owner away, every GPU below its cap, at
     *     least the disk asked for free beyond what its owner keeps (a host that hasn't reported its free disk has
     *     none), no live session
     * @param cachedImages the job images already on its machine, as digests, from its last heartbeat
     */
    record Looked(SchedulableHosts.Candidate candidate, boolean online, boolean free, List<String> cachedImages) {}

    /**
     * Candidates for a booking, best first.
     *
     * @param presenceKnown presence was read: when it wasn't, an empty list says nothing about whether a host could
     *     take the session, and the booking isn't ended for it (SessionMatcher)
     */
    record Found(List<Scoring.Scored> ranked, boolean presenceKnown) {}

    /** Each listed host, with whether it's online and free for a session that needs {@code diskNeededMb}. */
    List<Looked> look(List<SchedulableHosts.Candidate> listed, int diskNeededMb) {
        return lookAll(listed, diskNeededMb).hosts();
    }

    private record Looks(List<Looked> hosts, boolean presenceKnown) {}

    private Looks lookAll(List<SchedulableHosts.Candidate> listed, int diskNeededMb) {
        if (listed.isEmpty()) {
            return new Looks(List.of(), true);
        }
        var ids = listed.stream().map(SchedulableHosts.Candidate::hostId).toList();
        var lookup = presence.lookup(ids);
        var live = lookup.agents();
        var busy = sessions.hostsWithLiveSessions(ids);
        return new Looks(
                listed.stream()
                        .map(candidate -> {
                            var agent = live.get(candidate.hostId());
                            boolean free = agent != null
                                    && !candidate.paused()
                                    && !agent.updateRequired()
                                    && !agent.ownerActive()
                                    && agent.gpuTempsC().stream().allMatch(temp -> temp < candidate.gpuTempCapC())
                                    && agent.freeDiskMb() != null
                                    && agent.freeDiskMb() - candidate.keepFreeMb() >= diskNeededMb
                                    && !busy.contains(candidate.hostId());
                            return new Looked(
                                    candidate,
                                    agent != null && !candidate.paused(),
                                    free,
                                    agent == null ? List.of() : agent.cachedImages());
                        })
                        .toList(),
                lookup.known());
    }

    /**
     * Free hosts at or under {@code maxPricePaisePerMin} with at least {@code vramNeededMb} and {@code diskNeededMb}, whose
     * GPU's driver runs {@code cudaNeeded} when it's set.
     */
    List<SchedulableHosts.Candidate> free(
            int maxPricePaisePerMin,
            int vramNeededMb,
            int diskNeededMb,
            @Nullable String cudaNeeded,
            Collection<UUID> excluded) {
        return freeLooked(maxPricePaisePerMin, vramNeededMb, diskNeededMb, cudaNeeded, excluded).stream()
                .map(Looked::candidate)
                .toList();
    }

    private List<Looked> freeLooked(
            int maxPricePaisePerMin,
            int vramNeededMb,
            int diskNeededMb,
            @Nullable String cudaNeeded,
            Collection<UUID> excluded) {
        return freeLooks(maxPricePaisePerMin, vramNeededMb, diskNeededMb, cudaNeeded, excluded)
                .hosts();
    }

    private Looks freeLooks(
            int maxPricePaisePerMin,
            int vramNeededMb,
            int diskNeededMb,
            @Nullable String cudaNeeded,
            Collection<UUID> excluded) {
        var listed = hosts.batchCandidates(maxPricePaisePerMin, vramNeededMb).stream()
                .filter(candidate -> !excluded.contains(candidate.hostId()))
                .filter(candidate ->
                        cudaNeeded == null || CudaDrivers.supports(candidate.gpuDriverVersion(), cudaNeeded))
                .toList();
        var looks = lookAll(listed, diskNeededMb);
        return new Looks(looks.hosts().stream().filter(Looked::free).toList(), looks.presenceKnown());
    }

    /** Candidates, best first, and whether presence was known. */
    Found find(SessionBookings.BookingRequest request, Collection<UUID> alreadyOffered) {
        var digest = Scoring.digest(request.spec().getImage());
        var looks = freeLooks(
                request.maxPricePaisePerMin(),
                request.vramNeededMb(),
                request.diskNeededMb(),
                request.cudaNeeded(),
                alreadyOffered);
        return new Found(
                looks.hosts().stream()
                        .map(looked -> {
                            var candidate = looked.candidate();
                            return new Scoring.Scored(
                                    new Scoring.Candidate(
                                            candidate.hostId(), candidate.pricePaisePerMin(), candidate.gpuUuid()),
                                    Scoring.score(
                                            candidate.pricePaisePerMin(),
                                            request.maxPricePaisePerMin(),
                                            candidate.reliability(),
                                            candidate.bandwidth(),
                                            looked.cachedImages().contains(digest)));
                        })
                        .sorted(Scoring.BEST_FIRST)
                        .toList(),
                looks.presenceKnown());
    }
}
