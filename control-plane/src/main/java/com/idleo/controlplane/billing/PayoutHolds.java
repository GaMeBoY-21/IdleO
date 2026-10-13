package com.idleo.controlplane.billing;

import java.util.UUID;

/**
 * A session's host earnings are held from payout while a report about it is open (ADR 0028). Payouts don't exist
 * yet; the hold is recorded now, shown on the owner's earnings, and payouts will skip what it holds. The trust module
 * places and ends holds, each inside its own database transaction.
 */
public interface PayoutHolds {

    /** Holds the session's host earnings for this report. Holding again for the same report changes nothing. */
    void hold(UUID disputeId, UUID sessionId);

    /** Ends the hold this report placed, if it's still on. */
    void release(UUID disputeId);
}
