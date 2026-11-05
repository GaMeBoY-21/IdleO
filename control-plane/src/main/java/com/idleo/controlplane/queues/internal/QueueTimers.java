package com.idleo.controlplane.queues.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An item's timer and priority, worked out from what's known of it now, with nothing read or written (ADR 0053), so a
 * controlled clock can test each case.
 */
final class QueueTimers {

    /** A label's lowest score, and what an admin's override to it scores. */
    static final Map<String, Integer> OVERRIDE = Map.of("urgent", 300, "high", 150, "normal", 80, "low", 20);

    private static final int URGENT = 200;
    private static final int HIGH = 120;
    private static final int NORMAL = 50;
    private static final int MOST_FOR_AGE = 200;
    private static final long RANK_STEP = 100_000_000_000L;
    private static final long NO_DUE_DATE = 19_999_999_999L;
    private static final long LATEST = 20_000_000_000L;

    private QueueTimers() {}

    /** The target an item is timed to. */
    record Target(@Nullable Integer firstMinutes, @Nullable Integer resolveMinutes, boolean pauses) {}

    /** What's known: {@code providerDue} is a deadline that isn't ours; the rest come from the item and its target. */
    record Facts(
            Target target,
            Instant openedAt,
            @Nullable Instant providerDue,
            @Nullable Instant firstReplyAt,
            boolean paused,
            long pausedMs,
            long moneyPaise,
            boolean runningSession,
            boolean vip,
            boolean fraudSuspect,
            @Nullable Integer override,
            Duration warnBeforeProviderDue) {}

    /** The results: no due time is a timer that's paused, or a target met. */
    record Timer(@Nullable Instant dueAt, @Nullable Instant soonAt, int score, long sortKey) {}

    static Timer of(Facts facts, Instant now) {
        var pausedFor = Duration.ofMillis(facts.pausedMs());
        Instant due;
        Duration window;
        if (facts.providerDue() != null) {
            due = facts.providerDue();
            window = Duration.between(facts.openedAt(), due);
        } else if (facts.target().firstMinutes() != null && facts.firstReplyAt() == null) {
            window = Duration.ofMinutes(facts.target().firstMinutes());
            due = facts.openedAt().plus(window).plus(pausedFor);
        } else if (facts.target().resolveMinutes() != null) {
            window = Duration.ofMinutes(facts.target().resolveMinutes());
            due = facts.openedAt().plus(window).plus(pausedFor);
        } else {
            window = null;
            due = null;
        }
        if (facts.paused() && facts.providerDue() == null) {
            due = null;
        }
        Instant soon = null;
        if (due != null && window != null) {
            soon = facts.providerDue() != null
                    ? due.minus(facts.warnBeforeProviderDue())
                    : due.minus(window.dividedBy(4));
        }
        int age = 0;
        if (window != null && !window.isZero() && !window.isNegative()) {
            var running = Duration.between(facts.openedAt(), now).minus(pausedFor);
            age = Math.clamp(100 * running.toSeconds() / Math.max(1, window.toSeconds()), 0, MOST_FOR_AGE);
        }
        int score = age + forMoney(facts.moneyPaise());
        score += facts.runningSession() ? 100 : 0;
        score += facts.vip() ? 50 : 0;
        score += facts.fraudSuspect() ? 75 : 0;
        return new Timer(due, soon, score, sortKey(facts.override() != null ? facts.override() : score, due));
    }

    /** Priority first, then the soonest due: one number, so the list can page by it. */
    static long sortKey(int rank, @Nullable Instant due) {
        return rank * RANK_STEP + (LATEST - (due == null ? NO_DUE_DATE : Math.min(due.getEpochSecond(), NO_DUE_DATE)));
    }

    private static int forMoney(long paise) {
        if (paise >= 1_000_000) {
            return 50;
        }
        if (paise >= 100_000) {
            return 25;
        }
        return paise >= 10_000 ? 10 : 0;
    }

    static String label(int rank) {
        if (rank >= URGENT) {
            return "urgent";
        }
        if (rank >= HIGH) {
            return "high";
        }
        return rank >= NORMAL ? "normal" : "low";
    }
}
