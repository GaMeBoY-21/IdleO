package com.idleo.controlplane.billing;

import java.util.UUID;

/**
 * Whether a person has a verified PAN (ADR 0040): a renter buying more than {@code legal.payouts.renter-pan-above-paise}
 * of credit in a financial year gives one first. Billing declares this port and the payouts module, which keeps PANs,
 * implements it, because payouts already depends on billing.
 */
public interface VerifiedPans {

    boolean verified(UUID userId);
}
