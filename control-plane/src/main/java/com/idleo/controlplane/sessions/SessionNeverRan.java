package com.idleo.controlplane.sessions;

import java.time.Instant;
import java.util.UUID;

/**
 * A session reached a final state without ever running: no host was free, or it failed before its first RUNNING
 * (ADR 0026). Nothing was charged. One a renter cancelled themselves doesn't send it. It says whose it was and when
 * it ended, so its renter can report it (ADR 0028). Durable, like the others.
 *
 * @param state its final state: NO_HOST_FREE or FAILED
 */
public record SessionNeverRan(UUID sessionId, UUID renterId, String state, Instant endedAt) {}
