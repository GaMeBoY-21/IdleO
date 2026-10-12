package com.idleo.controlplane.identity.internal;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The request limits (technical design "Limits", ADR 0025): requests a minute for each signed-in person, or for each
 * address when there's no sign-in, and CLI sign-ins started an hour from one address.
 */
@ConfigurationProperties("limits")
@Validated
record RequestLimitsProperties(
        @DefaultValue("60") @Positive int requestsPerMinute,
        @DefaultValue("10") @Positive int cliLoginsPerHour) {}
