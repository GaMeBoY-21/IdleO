package com.idleo.controlplane.hosts;

import java.util.List;
import java.util.UUID;

/** Who owns a host, for modules that show a host's data to its owner only (ADR 0010). */
public interface HostOwners {

    /** Whether {@code userId} owns {@code hostId}; false for a host that doesn't exist. */
    boolean owns(UUID userId, UUID hostId);

    /** One of a user's machines. A removed one is kept, because the ledger still points at it. */
    record OwnedHost(UUID id, String name, boolean removed) {}

    /** The user who owns the host, removed or not; empty for a host that doesn't exist. */
    java.util.Optional<UUID> ownerOf(UUID hostId);

    /** Every machine the user owns, removed ones too, oldest first. */
    List<OwnedHost> ownedBy(UUID userId);

    /**
     * The owners of machines not removed whose agent runs a version from {@code minAgentVersion} to {@code
     * maxAgentVersion} (each inclusive, either left out), for a broadcast notice (ADR 0056); every owner with neither.
     */
    List<UUID> ownersRunning(
            @org.jspecify.annotations.Nullable String minAgentVersion,
            @org.jspecify.annotations.Nullable String maxAgentVersion);
}
