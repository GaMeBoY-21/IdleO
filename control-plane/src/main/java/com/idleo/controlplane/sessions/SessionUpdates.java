package com.idleo.controlplane.sessions;

import java.util.UUID;

/**
 * Tells the sessions module that something about a session changed outside it (ADR 0019), such as a settlement
 * metering posted, so its open event streams send the new state at once instead of at their next re-read. Call it
 * inside the transaction that made the change; the hint goes out after it commits.
 */
public interface SessionUpdates {

    void changed(UUID sessionId);
}
