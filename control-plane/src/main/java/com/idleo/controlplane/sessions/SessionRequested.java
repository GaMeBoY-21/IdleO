package com.idleo.controlplane.sessions;

import java.util.UUID;

/**
 * A session is waiting for a host: a renter asked for it (ADR 0015), or its host was lost and its next attempt
 * needs one (ADR 0016). Recorded durably with the change that caused it.
 */
public record SessionRequested(UUID sessionId) {}
