package com.idleo.controlplane.payouts;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A payout reached an end the host is told about (ADR 0040), once each: {@code processed} (the money left for their UPI
 * ID), {@code failed} (it never left: it's back in their earnings and the next run tries again), or {@code reversed}
 * (their bank sent it back: it's back in their earnings, and they're asked to check their UPI ID). Published in the
 * payout's own transaction.
 *
 * @param why the plain words for a failure or a reversal, null for a processed one
 */
public record PayoutSettled(
        UUID payoutId,
        UUID userId,
        String outcome,
        long netPaise,
        long tdsPaise,
        @Nullable String why) {}
