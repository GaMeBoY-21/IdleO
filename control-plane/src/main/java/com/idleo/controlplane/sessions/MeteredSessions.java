package com.idleo.controlplane.sessions;

import java.time.Duration;
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
     */
    record Attempt(
            int attempt,
            UUID hostId,
            int pricePaisePerMin,
            int minuteIndexBase,
            @Nullable Instant startDeliveredAt,
            @Nullable Instant runningAt,
            @Nullable Instant endedAt) {}

    Optional<Session> session(UUID sessionId);

    /** The name of the service each session ran ("Fine-tune a model"), for those whose template is still in the catalog. */
    java.util.Map<UUID, String> serviceNames(java.util.Collection<UUID> sessionIds);

    /** The session's attempts, first first. */
    List<Attempt> attempts(UUID sessionId);

    /**
     * When the attempt's newest complete checkpoint was written, on storage's clock: storage is listed, so one
     * finished just before its host died counts though the agent never reported it. Empty if it has none.
     */
    Optional<Instant> lastCompleteCheckpointAt(UUID sessionId, int attempt);

    /** Whether one unbroken stretch of the attempt's lease, as the control plane recorded it, covers [from, until]. */
    boolean leaseCovered(UUID sessionId, int attempt, Instant from, Instant until);

    /** How long each lease lasts: the credit must always cover the next one. */
    Duration leaseTtl();

    /**
     * The minutes a lease sent at {@code sentAt} would let the attempt start beyond those it has already started:
     * what the credit must still cover before that lease is sent (ADR 0017). The attempt's current minute is
     * already counted against the credit, so only minutes starting after it count; before its first RUNNING, every
     * minute the lease spans does.
     */
    static long minutesALeaseStarts(@Nullable Instant runningAt, Instant sentAt, Duration ttl) {
        var leaseEnd = sentAt.plus(ttl);
        if (runningAt == null || sentAt.isBefore(runningAt)) {
            return (ttl.toSeconds() + 59) / 60;
        }
        var currentMinuteEnd = runningAt.plus(
                Duration.ofMinutes(Duration.between(runningAt, sentAt).toMinutes() + 1));
        if (!leaseEnd.isAfter(currentMinuteEnd)) {
            return 0;
        }
        var beyond = Duration.between(currentMinuteEnd, leaseEnd);
        return (beyond.toSeconds() + 59) / 60;
    }
}
