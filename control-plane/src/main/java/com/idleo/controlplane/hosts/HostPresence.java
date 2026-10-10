package com.idleo.controlplane.hosts;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Whether hosts are online right now. The hosts module declares this port and the agent gateway
 * implements it from its presence records (ADR 0013), because the agent gateway already depends on
 * hosts: a call the other way would be a module cycle.
 *
 * <p>Implementations never throw. When presence can't be read, every host counts as offline, so a
 * host page never fails because presence is unavailable.
 */
public interface HostPresence {

    /** The hosts among {@code hostIds} that have a live agent stream. */
    Set<UUID> online(Collection<UUID> hostIds);

    /**
     * A live agent's stream, for its owner's view of the machine.
     *
     * @param since when the stream opened
     * @param updateRequired the agent is older than the oldest release the control plane supports
     */
    record Connection(Instant since, boolean updateRequired) {}

    /** The live streams among {@code hostIds}; a host with none isn't in the map. Empty when presence can't be read. */
    Map<UUID, Connection> connections(Collection<UUID> hostIds);

    /** Whether this host has a live agent stream. */
    default boolean isOnline(UUID hostId) {
        return online(List.of(hostId)).contains(hostId);
    }
}
