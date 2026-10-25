package com.idleo.controlplane.hosts;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * How hosts' sessions have gone, for the host score. The hosts module declares this port and the sessions module
 * implements it, because sessions are its own and a dependency from hosts on sessions would point the wrong way.
 * Only the host's own failures are lost: a renter stopping a session, a job failing and an owner coming back to the
 * machine (the attempt saved a checkpoint and went on elsewhere) are not.
 */
public interface HostSessionResults {

    /**
     * One session's result for the host that ran it.
     *
     * @param at when the attempt ended, on the control plane's clock
     * @param finished the session ran to its end on this host; false if the host lost it (going offline, its lease
     *     lapsing, its agent stopping it)
     */
    record Result(UUID hostId, Instant at, boolean finished) {}

    /** The results since {@code since}, for these hosts. */
    List<Result> since(Collection<UUID> hostIds, Instant since);
}
