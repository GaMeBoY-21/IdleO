package com.idleo.controlplane.identity.internal;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The request limits: requests a minute for each signed-in person, or for each
 * address when there's no sign-in; reads of a session's logs a minute, which a live log view makes often and so have
 * their own count; CLI sign-ins started an hour from one address; host links asked for an hour from one address; and
 * batches of real visits' vitals a minute from one address (ADR 0036), counted apart from everything else; and
 * messages to support an hour, from each person or address (ADR 0037), signed in or not.
 */
@ConfigurationProperties("limits")
@Validated
record RequestLimitsProperties(
        @DefaultValue("60") @Positive int requestsPerMinute,
        @DefaultValue("10") @Positive int cliLoginsPerHour,
        @DefaultValue("10") @Positive int hostLinksPerHour,
        @DefaultValue("120") @Positive int logReadsPerMinute,
        @DefaultValue("30") @Positive int vitalsPerMinute,
        @DefaultValue("5") @Positive int supportMessagesPerHour) {}
