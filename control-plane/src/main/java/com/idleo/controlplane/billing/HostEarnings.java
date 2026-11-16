package com.idleo.controlplane.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a host earned, read from the ledger's {@code host_payable} entries and nowhere else, so it always matches the
 * ledger (ADR 0023). Sessions asks for its host-facing views, and the agent gateway for the tray's today.
 */
public interface HostEarnings {

    /**
     * What one session paid a host.
     *
     * @param creditedPaise the host's share of what was charged
     * @param givenBackPaise what was taken back for lost time
     */
    record Earned(long creditedPaise, long givenBackPaise) {

        public static final Earned NONE = new Earned(0, 0);

        public long netPaise() {
            return creditedPaise - givenBackPaise;
        }
    }

    /** What each of these sessions paid the host; a session that paid it nothing yet isn't in the map. */
    Map<UUID, Earned> bySession(UUID hostId, Collection<UUID> sessionIds);

    /** The host's share of an amount charged but not settled yet, split the way a settlement splits it. */
    long shareOf(long chargedPaise);

    /**
     * What the host has earned today, India's calendar day, less what was given back: the same query and day as the
     * owner's Earnings, for the host's tray (ADR 0034).
     */
    long earnedToday(UUID hostId);

    /** What the host has earned this week, Monday to now in India's calendar, less what was given back. */
    long earnedThisWeek(UUID hostId);

    /** What the host has earned this month, in India's calendar, less what was given back. */
    long earnedThisMonth(UUID hostId);

    /**
     * A payout that took the host's earnings: when it was made up and how much of the host's earnings it took, before any
     * deductions.
     */
    record Payout(Instant sentAt, long amountPaise) {}

    /** The latest payout that took the host's earnings, if any has. */
    Optional<Payout> lastPayout(UUID hostId);

    /** The date of the next weekly payout in India, while payouts are running; none while they aren't. */
    Optional<LocalDate> nextPayoutOn();
}
