package com.idleo.controlplane.sessions;

import java.util.UUID;

/**
 * A session reached a final state without ever running: no host was free, or it failed before its first RUNNING
 * (ADR 0026). Nothing was charged. One a renter cancelled themselves doesn't send it. Durable, like the others.
 */
public record SessionNeverRan(UUID sessionId) {}
