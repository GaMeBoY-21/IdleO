package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.OneAtATime;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What a stream's opening and closing can't record (ADR 0056): a record that lapsed with no close (an agent gone without
 * a word, an instance that died), and Valkey not answering. Every {@code agentgateway.presence-history.sweep-every},
 * on one instance at a time, it looks up every host last recorded online or unknown: one with no record is offline, and
 * while presence can't be read, one recorded online is unknown until it can.
 */
@Component
class PresenceHistorySweep {

    private static final Logger log = LoggerFactory.getLogger(PresenceHistorySweep.class);

    private final PresenceHistory history;
    private final Presence presence;
    private final OneAtATime oneAtATime;
    private final Clock clock;

    PresenceHistorySweep(PresenceHistory history, Presence presence, OneAtATime oneAtATime, Clock clock) {
        this.history = history;
        this.presence = presence;
        this.oneAtATime = oneAtATime;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${agentgateway.presence-history.sweep-every:30s}",
            initialDelayString = "${agentgateway.presence-history.sweep-every:30s}")
    void sweep() {
        try {
            oneAtATime.run("agentgateway:presence-history", this::sweepNow);
        } catch (RuntimeException e) {
            log.warn("The presence history's sweep failed; it runs again shortly", e);
        }
    }

    void sweepNow() {
        var recorded = history.notOffline();
        if (recorded.isEmpty()) {
            return;
        }
        // Before the lookup: a stream that opens after it is newer than what the lookup saw, and wins.
        var observedAt = clock.instant();
        var lookup = presence.lookup(
                recorded.stream().map(PresenceHistory.Recorded::hostId).toList());
        for (var host : recorded) {
            if (!lookup.known()) {
                if (host.state() == PresenceHistory.State.ONLINE) {
                    history.unknown(host.hostId(), observedAt);
                }
            } else if (lookup.agents().containsKey(host.hostId())) {
                if (host.state() == PresenceHistory.State.UNKNOWN) {
                    history.seenOnline(host.hostId(), observedAt);
                }
            } else {
                history.offline(host.hostId(), observedAt);
            }
        }
    }
}
