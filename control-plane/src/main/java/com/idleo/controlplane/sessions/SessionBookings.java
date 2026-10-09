package com.idleo.controlplane.sessions;

import gpu.agent.v1.Session.SessionSpec;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the scheduler may read and change about a session while booking it (ADR 0015). Every change is a
 * transition of the session state machine, runs only inside the caller's transaction, and returns false,
 * changing nothing, if the session isn't in a state that allows it.
 */
public interface SessionBookings {

    /**
     * What a session needs from a host, and what the renter will pay.
     *
     * @param attempt the attempt being booked: 1, and one more after each lost host
     * @param spec what it runs, as the agent's Offer and StartSession carry it
     * @param diskNeededMb the free disk a host needs for its uploaded inputs and scratch disk, with a margin
     */
    record BookingRequest(
            UUID sessionId,
            UUID renterId,
            String state,
            int version,
            int attempt,
            SessionSpec spec,
            int vramNeededMb,
            int diskNeededMb,
            int maxPricePaisePerMin,
            long maxSpendPaise) {}

    Optional<BookingRequest> bookingRequest(UUID sessionId);

    /** REQUESTED to MATCHED on {@code hostId} at {@code pricePaisePerMin}, if the session is still at {@code version}. */
    boolean match(UUID sessionId, int expectedVersion, UUID hostId, int pricePaisePerMin);

    /**
     * MATCHED to PROVISIONING: the host's agent accepted the offer to run the session on {@code gpuUuid}. In
     * the same transaction, the attempt is recorded and its StartSession queued for the host (ADR 0016).
     */
    boolean offerAccepted(UUID sessionId, String gpuUuid);

    /** MATCHED back to REQUESTED: the host declined or let the offer expire. */
    boolean offerDeclined(UUID sessionId, String reason);

    /** REQUESTED or MATCHED to NO_HOST_FREE. */
    boolean noHostFree(UUID sessionId, String reason);

    /** REQUESTED to CANCELLED by the system, for example when the renter's credit no longer covers the hold. */
    boolean cancel(UUID sessionId, String reason);

    /** A session being matched again after a lost host, REQUESTED since {@code since}. */
    record AwaitingRematch(UUID sessionId, Instant since) {}

    /** Sessions being matched again after a lost host (attempt 2 or later) that are waiting in REQUESTED. */
    List<AwaitingRematch> awaitingRematch();

    /**
     * Whether the session has run (it has a minute 0). A session that ran keeps its hold to the end, when metering
     * settles and releases it (ADR 0017); one that never ran has its hold released by whoever ends it.
     */
    boolean hasRun(UUID sessionId);

    /** The hosts among {@code hostIds} that have a live session. */
    Set<UUID> hostsWithLiveSessions(Collection<UUID> hostIds);
}
