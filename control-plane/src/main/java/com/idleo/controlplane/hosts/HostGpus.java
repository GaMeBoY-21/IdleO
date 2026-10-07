package com.idleo.controlplane.hosts;

import java.util.Optional;
import java.util.UUID;

/**
 * What GPU ran a session, for the renter's view (ADR 0019, product surface 2.7): its model and memory, never the
 * host's identity or owner.
 */
public interface HostGpus {

    /** A GPU's model and memory. */
    record Gpu(String model, int vramMb) {}

    /** The GPU {@code gpuUuid} of host {@code hostId}, as its agent last reported it; empty if unknown. */
    Optional<Gpu> gpu(UUID hostId, String gpuUuid);
}
