package com.idleo.controlplane.sessions.internal;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** The renter's session limits (technical design "Limits", ADR 0025; see {@link SessionLimits}). */
@ConfigurationProperties("sessions.limits")
@Validated
record SessionLimitProperties(
        @DefaultValue("10") @Positive int newPerHour,
        @DefaultValue("2") @Positive int newPerHourFirstDay,
        @DefaultValue("3") @Positive int atOnce) {}
