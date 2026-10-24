package com.idleo.controlplane.billing;

import java.time.Instant;
import java.util.UUID;

/**
 * Earnings already paid out were refunded to a renter (ADR 0040): the owner's balance covered {@code fromBalancePaise}
 * at once and they owe {@code owedPaise}, recovered from what they earn next. Published in the refund's own
 * transaction, so the owner is told once.
 */
public record EarningsClawedBack(
        UUID clawbackId,
        UUID ownerId,
        UUID sessionId,
        long amountPaise,
        long fromBalancePaise,
        long owedPaise,
        Instant at) {}
