package com.idleo.controlplane.hosts;

import java.util.UUID;

/** Who owns a host, for modules that show a host's data to its owner only (ADR 0010). */
public interface HostOwners {

    /** Whether {@code userId} owns {@code hostId}; false for a host that doesn't exist. */
    boolean owns(UUID userId, UUID hostId);
}
