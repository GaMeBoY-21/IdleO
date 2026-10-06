package com.idleo.controlplane.sessions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What metering may read about a session (ADR 0017): who pays, its cap, its minute 0, and, for each attempt,
 * when it ran on the control plane's clock and whether the control plane's own lease record covered a stretch
 * of time. Read-only.
 */
public interface MeteredSessions {

    /**
     * @param startedAt minute 0: the first RUNNING on the control plane's clock; null if it never ran
     * @param attempt the current attempt
     */
    record Session(
            UUID sessionId,
            UUID renterId,
            long maxSpendPaise,
            @Nullable Instant startedAt,
            int attempt,
            @Nullable UUID hostId,
            boolean live) {}

    /**
     * One attempt.
     *
     * @param runningAt its first RUNNING, received by the control plane; null if it never ran
     * @param endedAt when it stopped running, as the control plane learned it; null while it runs
     * @param lastCheckpointAt when its newest complete checkpoint was written; null if it has none
     */
    record Attempt(
            int attempt,
            UUID hostId,
            int pricePaisePerMin,
            int minuteIndexBase,
            @Nullable Instant startDeliveredAt,
            @Nullable Instant runningAt,
            @Nullable Instant endedAt,
            @Nullable Instant lastCheckpointAt) {}

    Optional<Session> session(UUID sessionId);

    /** The session's attempts, first first. */
    List<Attempt> attempts(UUID sessionId);

    /** Whether one unbroken stretch of the attempt's lease, as the control plane recorded it, covers [from, until]. */
    boolean leaseCovered(UUID sessionId, int attempt, Instant from, Instant until);
}
