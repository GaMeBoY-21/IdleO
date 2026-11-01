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
     * The minutes a lease sent at {@code sentAt} would let the attempt start beyond those already counted against the
     * credit: what the credit must still cover before that lease is sent (ADR 0017). Metering's count at {@code
     * countedAt} includes every minute started by then, the one in progress too; a minute counts as started by a
     * lease once it begins before the lease ends, even a moment before. A count from an earlier minute, or none
     * ({@code countedAt} null or before the attempt ran), leaves the minutes started since to the lease. Before its
     * first RUNNING, every minute the lease spans counts.
     */
    static long minutesALeaseStarts(
            @Nullable Instant runningAt, @Nullable Instant countedAt, Instant sentAt, Duration ttl) {
        if (runningAt == null) {
            return wholeMinutesIn(ttl);
        }
        var leaseEnd = sentAt.plus(ttl);
        long startedByLeaseEnd =
                leaseEnd.isAfter(runningAt) ? wholeMinutesIn(Duration.between(runningAt, leaseEnd)) : 0;
        long counted = countedAt == null || countedAt.isBefore(runningAt)
                ? 0
                : Duration.between(runningAt, countedAt).toMinutes() + 1;
        return Math.max(0, startedByLeaseEnd - counted);
    }

    /** How many minutes a stretch this long touches, the last partly: 60.5 seconds is two. */
    private static long wholeMinutesIn(Duration stretch) {
        long minutes = stretch.toMinutes();
        return stretch.equals(Duration.ofMinutes(minutes)) ? minutes : minutes + 1;
    }
}
