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
 * active, listed and big enough (hosts); online, current, owner away and every GPU below the cap (agent
 * gateway presence); no live session (sessions); and not already offered this session.
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
     * @param online its agent's presence is live
     * @param free it could take a session now: online, current, owner away, every GPU below its cap, no live session
     */
    record Looked(SchedulableHosts.Candidate candidate, boolean online, boolean free) {}

    /** Each listed host, with whether it's online and free. */
    List<Looked> look(List<SchedulableHosts.Candidate> listed) {
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
                            && !agent.updateRequired()
                            && !agent.ownerActive()
                            && agent.gpuTempsC().stream().allMatch(temp -> temp < candidate.gpuTempCapC())
                            && !busy.contains(candidate.hostId());
                    return new Looked(candidate, agent != null, free);
                })
                .toList();
    }

    /** Free hosts at or under {@code maxPricePaisePerMin} with at least {@code vramNeededMb}. */
    List<SchedulableHosts.Candidate> free(int maxPricePaisePerMin, int vramNeededMb, Collection<UUID> excluded) {
        var listed = hosts.batchCandidates(maxPricePaisePerMin, vramNeededMb).stream()
                .filter(candidate -> !excluded.contains(candidate.hostId()))
                .toList();
        return look(listed).stream().filter(Looked::free).map(Looked::candidate).toList();
    }

    /** Candidates, best first. */
    List<Scoring.Scored> find(SessionBookings.BookingRequest request, Collection<UUID> alreadyOffered) {
        return free(request.maxPricePaisePerMin(), request.vramNeededMb(), alreadyOffered).stream()
                .map(candidate -> new Scoring.Scored(
                        new Scoring.Candidate(candidate.hostId(), candidate.pricePaisePerMin(), candidate.gpuUuid()),
                        Scoring.score(candidate.pricePaisePerMin(), request.maxPricePaisePerMin())))
                .sorted(Scoring.BEST_FIRST)
                .toList();
    }
}
