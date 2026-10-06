package com.idleo.controlplane.sessions;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the scheduler may read and change about a session while booking it (ADR 0015). Every change is a
 * transition of the session state machine, runs only inside the caller's transaction, and returns false,
 * changing nothing, if the session isn't in a state that allows it.
 */
public interface SessionBookings {

    /** What a session needs from a host, and what the renter will pay. */
    record BookingRequest(
            UUID sessionId,
            UUID renterId,
            String state,
            int version,
            Template template,
            int vramNeededMb,
            int maxPricePaisePerMin,
            long maxSpendPaise,
            Duration maxRuntime) {}

    Optional<BookingRequest> bookingRequest(UUID sessionId);

    /** REQUESTED to MATCHED on {@code hostId} at {@code pricePaisePerMin}, if the session is still at {@code version}. */
    boolean match(UUID sessionId, int expectedVersion, UUID hostId, int pricePaisePerMin);

    /** MATCHED to PROVISIONING: the host's agent accepted the offer. */
    boolean offerAccepted(UUID sessionId);

    /** MATCHED back to REQUESTED: the host declined or let the offer expire. */
    boolean offerDeclined(UUID sessionId, String reason);

    /** REQUESTED or MATCHED to NO_HOST_FREE. */
    boolean noHostFree(UUID sessionId, String reason);

    /** REQUESTED to CANCELLED by the system, for example when the renter's credit no longer covers the hold. */
    boolean cancel(UUID sessionId, String reason);

    /** The hosts among {@code hostIds} that have a live session. */
    Set<UUID> hostsWithLiveSessions(Collection<UUID> hostIds);
}
