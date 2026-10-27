package com.idleo.controlplane.hosts;

import java.util.Optional;
import java.util.UUID;

/**
 * What GPU ran a session, for the renter's view (ADR 0019): its model and memory, never the
 * host's identity or owner.
 */
public interface HostGpus {

    /** A GPU's model and memory. */
    record Gpu(String model, int vramMb) {}

    /** The GPU {@code gpuUuid} of host {@code hostId}, as its agent last reported it; empty if unknown. */
    Optional<Gpu> gpu(UUID hostId, String gpuUuid);

    /**
     * The host's largest reported GPU that isn't gone (ties by uuid): the one a session would run on. Empty before its
     * agent has reported any (ADR 0023).
     */
    Optional<Gpu> largest(UUID hostId);
}
