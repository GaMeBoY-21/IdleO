package com.idleo.controlplane.sessions;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the notifications module may read about how a session went (ADR 0026): who it belonged to, how it ended
 * and which host ran an attempt. Read-only; no other session detail leaves the module.
 */
public interface SessionOutcomes {

    /**
     * @param serviceName the service the session ran ("Fine-tune a model"), when its template is still in the catalog
     * @param state its state: COMPLETED, FAILED, NO_HOST_FREE, ENDED_REFUNDED, CANCELLED or a working one
     * @param endCode why it ended without completing: its error code, or its last event's reason; null when it
     *     completed
     * @param stoppedFor the reason it was told to stop (what moved it to FINISHING): {@code renter_stopped}, {@code
     *     credit_exhausted}, {@code max_spend_reached}; null when it wasn't stopped
     * @param startedAt its minute 0; null when it never ran
     */
    record Outcome(
            UUID sessionId,
            UUID renterId,
            @Nullable String serviceName,
            String state,
            @Nullable String endCode,
            @Nullable String stoppedFor,
            @Nullable Instant startedAt) {}

    Optional<Outcome> of(UUID sessionId);

    /** The host the attempt ran on, or empty if it never had one. */
    Optional<UUID> hostOfAttempt(UUID sessionId, int attempt);
}
