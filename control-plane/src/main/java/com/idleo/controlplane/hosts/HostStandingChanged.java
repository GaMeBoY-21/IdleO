package com.idleo.controlplane.hosts;

import java.util.UUID;

/**
 * A host's status or pause changed (activated, suspended, paused, resumed or removed), so its agent's tray is told
 * where it stands now.
 */
public record HostStandingChanged(UUID hostId) {}
