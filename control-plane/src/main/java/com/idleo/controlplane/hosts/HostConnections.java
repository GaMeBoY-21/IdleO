package com.idleo.controlplane.hosts;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How often hosts' agents connected (ADR 0056), from the agent gateway's presence history: what a staged rollout's
 * automatic pause and the fleet view count reconnects from. The hosts module declares this port and the agent gateway
 * implements it, since the agent gateway already depends on hosts (ADR 0013).
 */
public interface HostConnections {

    /** How many streams each of these hosts opened since {@code since}; a host with none isn't in the map. */
    Map<UUID, Long> streamsOpened(Collection<UUID> hostIds, Instant since);
}
