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
 * <p>Implementations never throw. When presence can't be read, {@link #seen} says so (its hosts' state is
 * {@link State#UNKNOWN}, never offline, so no host is told its laptop is offline because Valkey didn't answer);
 * {@link #online} and {@link #connections} then count every host as offline, so a page never fails because presence
 * is unavailable.
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

    /** Where a host stands: its agent is connected, or isn't, or presence couldn't be read to tell. */
    enum State {
        ONLINE,
        OFFLINE,
        UNKNOWN
    }

    /**
     * One presence lookup.
     *
     * @param connections the live streams among the hosts asked about
     * @param known presence was read: false when Valkey couldn't answer, which says nothing about any host
     */
    record Seen(Map<UUID, Connection> connections, boolean known) {

        public State state(UUID hostId) {
            if (!known) {
                return State.UNKNOWN;
            }
            return connections.containsKey(hostId) ? State.ONLINE : State.OFFLINE;
        }
    }

    /** The live streams among {@code hostIds}, in one round trip, and whether presence could be read at all. */
    Seen seen(Collection<UUID> hostIds);

    /** Whether this host has a live agent stream. */
    default boolean isOnline(UUID hostId) {
        return online(List.of(hostId)).contains(hostId);
    }
}
