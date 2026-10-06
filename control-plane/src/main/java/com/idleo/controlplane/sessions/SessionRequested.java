package com.idleo.controlplane.sessions;

import java.util.UUID;

/** A renter asked for a session; it's waiting for a host (ADR 0015). Published after commit. */
public record SessionRequested(UUID sessionId) {}
