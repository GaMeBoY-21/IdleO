package com.idleo.controlplane.billing.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The pepper for payers' keyed hashes, from a SOPS Secret (docs/runbooks/payer-upi-pepper.md): 32 bytes in base64, or a
 * {@code file:} location holding that. Rotating adds a version and keeps the last pepper as {@code previousPepper} for as
 * long as old hashes should still link to new payments.
 */
@ConfigurationProperties("billing.payer-hash")
record PayerHashProperties(
        @Nullable String pepper,
        @DefaultValue("1") int pepperVersion,
        @Nullable String previousPepper,
        @DefaultValue("0") int previousPepperVersion) {

    @Override
    public String toString() {
        return "PayerHashProperties[version " + pepperVersion + "]";
    }
}
