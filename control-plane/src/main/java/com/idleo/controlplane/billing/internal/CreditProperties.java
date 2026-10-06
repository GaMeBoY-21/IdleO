package com.idleo.controlplane.billing.internal;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on free credit grants (ADR 0014), which bound the damage of a typo.
 *
 * @param maxGrantPaise the most one grant may give
 * @param maxWalletPaise a grant is refused if it would take the wallet's balance above this
 */
@ConfigurationProperties("billing.credits")
@Validated
record CreditProperties(
        @Positive long maxGrantPaise, @Positive long maxWalletPaise) {}
