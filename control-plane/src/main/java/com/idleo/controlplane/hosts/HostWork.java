package com.idleo.controlplane.hosts;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What hosts need to know about the work on a machine. The hosts module declares this port and the sessions module
 * implements it, because sessions already depends on hosts and a call the other way would be a module cycle (ADR 0013).
 */
public interface HostWork {

    /** Whether the host has a session that hasn't ended: matched, starting, running or finishing. */
    boolean isBusy(UUID hostId);

    /**
     * What a host is running now, without any renter: the service, and what the host has earned from it so far.
     *
     * @param serviceName the service's name ("Fine-tune a model")
     * @param runningLabel what it's doing ("Fine-tuning")
     * @param startedAt when it started running, on the control plane's clock
     * @param earnedSoFarPaise the host's share of what's charged and accrued so far (ADR 0017), from the ledger
     */
    record CurrentSession(String serviceName, String runningLabel, Instant startedAt, long earnedSoFarPaise) {}

    /** The hosts among {@code hostIds} that are running a session now; the others aren't in the map. */
    Map<UUID, CurrentSession> current(Collection<UUID> hostIds);

    /**
     * A session the host finished: how long it ran, and when it ended. Nothing of the renter's, and not the service.
     *
     * @param sessionId the session, so the ledger can say what it paid the host
     * @param endedAt when it ended
     * @param seconds how long it ran, from its first running to its end
     */
    record FinishedSession(UUID sessionId, Instant endedAt, long seconds) {}

    /** The host's last few finished sessions, newest first, at most {@code limit}. */
    List<FinishedSession> recent(UUID hostId, int limit);
}
