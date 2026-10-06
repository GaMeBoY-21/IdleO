package com.idleo.controlplane.scheduling.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Booking settings (ADR 0015).
 *
 * @param offerTtl how long an offer stays open (technical design line 228: 10 seconds)
 * @param maxOffers offers per session before it's NO_HOST_FREE (line 211: 3)
 * @param offerSweepInterval how often expired offers are looked for
 */
@ConfigurationProperties("scheduling")
@Validated
record SchedulingProperties(
        @NotNull Duration offerTtl,
        @Min(1) @Max(3) int maxOffers,
        @NotNull Duration offerSweepInterval) {}
