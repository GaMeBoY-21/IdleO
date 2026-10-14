package com.idleo.controlplane.identity.internal;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The request limits (technical design "Limits", ADR 0025): requests a minute for each signed-in person, or for each
 * address when there's no sign-in; reads of a session's logs a minute, which a live log view makes often and so have
 * their own count; and CLI sign-ins started an hour from one address.
 */
@ConfigurationProperties("limits")
@Validated
record RequestLimitsProperties(
        @DefaultValue("60") @Positive int requestsPerMinute,
        @DefaultValue("10") @Positive int cliLoginsPerHour,
        @DefaultValue("120") @Positive int logReadsPerMinute) {}
