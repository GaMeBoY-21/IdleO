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
     * @param keepFreeGb the disk, in GB, the owner keeps free for themselves: a session is offered only when the
     *     free disk less this still covers its files (ADR 0022)
     */
    record Listing(int batchPricePaisePerMin, int gpuTempCapC, int maxConcurrentSessions, int keepFreeGb) {

        /** What a host keeps free until its owner says otherwise. */
        public static final int DEFAULT_KEEP_FREE_GB = 20;

        public static final int MIN_KEEP_FREE_GB = 10;
        public static final int MAX_KEEP_FREE_GB = 500;
    }

    /** The host's listing, if its owner has set one. */
    Optional<Listing> listing(UUID hostId);
}
