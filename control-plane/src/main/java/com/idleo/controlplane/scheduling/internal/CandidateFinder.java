package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.agentgateway.AgentPresence;
import com.idleo.controlplane.hosts.SchedulableHosts;
import com.idleo.controlplane.sessions.SessionBookings;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Hosts that could take a session (technical design line 352), each fact from the module that owns it:
 * active, listed, not paused and big enough (hosts); online, current, owner away, every GPU below the cap, and room
 * on its disk for the session's files once the owner's share is kept free (agent gateway presence, ADRs 0022, 0023);
 * no live session (sessions); and not already offered this session.
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
     */
    record Looked(SchedulableHosts.Candidate candidate, boolean online, boolean free) {}

    /** Each listed host, with whether it's online and free for a session that needs {@code diskNeededMb}. */
    List<Looked> look(List<SchedulableHosts.Candidate> listed, int diskNeededMb) {
        if (listed.isEmpty()) {
            return List.of();
        }
        var ids = listed.stream().map(SchedulableHosts.Candidate::hostId).toList();
        var live = presence.live(ids);
        var busy = sessions.hostsWithLiveSessions(ids);
        return listed.stream()
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
                    return new Looked(candidate, agent != null && !candidate.paused(), free);
                })
                .toList();
    }

    /** Free hosts at or under {@code maxPricePaisePerMin} with at least {@code vramNeededMb} and {@code diskNeededMb}. */
    List<SchedulableHosts.Candidate> free(
            int maxPricePaisePerMin, int vramNeededMb, int diskNeededMb, Collection<UUID> excluded) {
        var listed = hosts.batchCandidates(maxPricePaisePerMin, vramNeededMb).stream()
                .filter(candidate -> !excluded.contains(candidate.hostId()))
                .toList();
        return look(listed, diskNeededMb).stream()
                .filter(Looked::free)
                .map(Looked::candidate)
                .toList();
    }

    /** Candidates, best first. */
    List<Scoring.Scored> find(SessionBookings.BookingRequest request, Collection<UUID> alreadyOffered) {
        return free(request.maxPricePaisePerMin(), request.vramNeededMb(), request.diskNeededMb(), alreadyOffered)
                .stream()
                .map(candidate -> new Scoring.Scored(
                        new Scoring.Candidate(candidate.hostId(), candidate.pricePaisePerMin(), candidate.gpuUuid()),
                        Scoring.score(candidate.pricePaisePerMin(), request.maxPricePaisePerMin())))
                .sorted(Scoring.BEST_FIRST)
                .toList();
    }
}
