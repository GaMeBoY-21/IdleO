package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.agentgateway.AgentPresence;
import com.idleo.controlplane.hosts.SchedulableHosts;
import com.idleo.controlplane.sessions.SessionBookings;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Hosts that could take a session (technical design line 333), each fact from the module that owns it:
 * active, listed and big enough (hosts); online, current, owner away and every GPU below the cap (agent
 * gateway presence); no live session (sessions); and not already offered this session.
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

    /** Candidates, best first. */
    List<Scoring.Scored> find(SessionBookings.BookingRequest request, Collection<UUID> alreadyOffered) {
        var listed = hosts.batchCandidates(request.maxPricePaisePerMin(), request.vramNeededMb()).stream()
                .filter(candidate -> !alreadyOffered.contains(candidate.hostId()))
                .toList();
        if (listed.isEmpty()) {
            return List.of();
        }
        var ids = listed.stream().map(SchedulableHosts.Candidate::hostId).toList();
        var live = presence.live(ids);
        var busy = sessions.hostsWithLiveSessions(ids);
        return listed.stream()
                .filter(candidate -> {
                    var agent = live.get(candidate.hostId());
                    return agent != null
                            && !agent.updateRequired()
                            && !agent.ownerActive()
                            && agent.gpuTempsC().stream().allMatch(temp -> temp < candidate.gpuTempCapC())
                            && !busy.contains(candidate.hostId());
                })
                .map(candidate -> new Scoring.Scored(
                        new Scoring.Candidate(candidate.hostId(), candidate.pricePaisePerMin(), candidate.gpuUuid()),
                        Scoring.score(candidate.pricePaisePerMin(), request.maxPricePaisePerMin())))
                .sorted(Scoring.BEST_FIRST)
                .toList();
    }
}
