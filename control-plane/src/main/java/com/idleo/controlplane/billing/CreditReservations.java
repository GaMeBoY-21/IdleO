package com.idleo.controlplane.billing;

import com.idleo.controlplane.Paise;
import java.util.Optional;
import java.util.UUID;

/**
 * Credit held for a booked session (ADR 0015). A hold isn't spent money: it's released when the session
 * ends without running, and later consumed by settlement. {@link #reserve} and {@link #release} only run
 * inside the caller's database transaction, because a booking is one atomic unit (technical design line
 * 32); called outside one, they throw.
 */
public interface CreditReservations {

    /** A booking holds this many minutes of credit at the session's price (technical design line 395). */
    int HOLD_MINUTES = 30;

    /** What a booking holds: 30 minutes at {@code pricePaisePerMin}, never more than {@code maxSpend}. */
    static Paise holdFor(long pricePaisePerMin, Paise maxSpend) {
        var thirtyMinutes = Paise.of(pricePaisePerMin).times(HOLD_MINUTES);
        return thirtyMinutes.isGreaterThan(maxSpend) ? maxSpend : thirtyMinutes;
    }

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
}
