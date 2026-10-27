package com.idleo.controlplane.billing;

import com.idleo.controlplane.Paise;
import java.util.Optional;
import java.util.UUID;

/**
 * Credit held for a booked session (ADR 0015). A hold isn't spent money: it's released when the session
 * ends, and in between each settlement charges from it and tops it back up (ADR 0017). {@link #reserve} and {@link #release} only run
 * inside the caller's database transaction, because a booking is one atomic unit; called outside one, they throw.
 */
public interface CreditReservations {

    /** A booking holds this many minutes of credit at the session's price. */
    int HOLD_MINUTES = 30;

    /**
     * What a booking holds: what the renter pays for 30 minutes at {@code pricePaisePerMin}, never more than {@code
     * maxSpend}.
     */
    default Paise holdFor(long pricePaisePerMin, Paise maxSpend) {
        var thirtyMinutes = renterCost(Paise.of(pricePaisePerMin).times(HOLD_MINUTES));
        return thirtyMinutes.isGreaterThan(maxSpend) ? maxSpend : thirtyMinutes;
    }

    /**
     * What the renter pays for {@code net} of listing prices (ADR 0039): every listing price is before GST, and a host
     * earns its share of it whatever the tax; a renter pays it plus GST once we're registered, and just the price until
     * then. Holds, spending caps, leases and charges are all in what the renter pays, so they all come through here.
     */
    Paise renterCost(Paise net);

    /** The GST percent added to renters' prices: zero while we aren't registered. */
    int gstPercent();

    /**
     * Holds {@code amount} of the user's available credit for the session. False, changing nothing, if
     * the available balance (balance minus holds) is too low. Holding again for a session that already
     * has an active hold changes nothing and returns true.
     */
    boolean reserve(UUID userId, UUID sessionId, Paise amount);

    /** Releases the session's active hold. False if it has none (already released, or never held). */
    boolean release(UUID sessionId);

    /** The user's balance minus their holds; zero before their first credit. */
    Paise available(UUID userId);

    /** The session's active hold, if any. */
    Optional<Paise> held(UUID sessionId);

    /**
     * Records what the session has used but not yet settled, as metering counts it (ADR 0017), so lease renewal
     * can tell whether the hold still covers a lease. Does nothing without an active hold.
     */
    void markCommitted(UUID sessionId, Paise committed);

    /** Whether the session's active hold, less what's committed against it, covers {@code needed}. */
    boolean covers(UUID sessionId, Paise needed);
}
