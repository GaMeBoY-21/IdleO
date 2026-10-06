package com.idleo.controlplane.hosts;

import java.util.Optional;
import java.util.UUID;

/** A host's listing, as its owner set it (ADR 0015). */
public interface HostListings {

    /**
     * What the host offers and its limits.
     *
     * @param batchPricePaisePerMin the price of a minute of batch work
     * @param gpuTempCapC the GPU temperature the host must stay below, sent to its agent in Welcome
     * @param maxConcurrentSessions how many sessions at once: always 1 for now
     */
    record Listing(int batchPricePaisePerMin, int gpuTempCapC, int maxConcurrentSessions) {}

    /** The host's listing, if its owner has set one. */
    Optional<Listing> listing(UUID hostId);
}
