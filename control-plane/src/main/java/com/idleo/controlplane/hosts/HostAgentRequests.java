package com.idleo.controlplane.hosts;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a host's agent asks of the hosts module for its owner, from the tray or the uninstaller, and where the host
 * stands for its tray. Pausing, resuming and leaving are the same code as the web app's Hosting tab (ADR 0023), acting
 * as the host's owner: one pause, shown in both places.
 */
public interface HostAgentRequests {

    /** Pauses the host until {@code until}, or until it's resumed when null; at most 7 days away. */
    void pause(UUID hostId, @Nullable Instant until);

    /** Ends the host's pause. */
    void resume(UUID hostId);

    /**
     * Removes the host for good, as its owner's Remove does: its certificates are revoked and its stream closes.
     * Refused with {@code host-busy} while it runs a session. Removing a removed host changes nothing.
     */
    void leave(UUID hostId);

    /** Where a host stands: {@code state} is pending, active, paused, suspended or removed. */
    record Standing(String state, @Nullable Instant pausedUntil) {}

    /** The host's standing now; empty for a host that doesn't exist. */
    Optional<Standing> standing(UUID hostId);
}
