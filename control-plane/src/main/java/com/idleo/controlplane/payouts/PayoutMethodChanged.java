package com.idleo.controlplane.payouts;

import java.time.Instant;
import java.util.UUID;

/**
 * A host's payout UPI ID changed (ADR 0040): they're told at once, by email too, so a change they didn't make is caught
 * before {@code holdUntil}, when the next payout may go to it. Published in the change's own transaction.
 */
public record PayoutMethodChanged(UUID userId, UUID methodId, Instant holdUntil) {}
