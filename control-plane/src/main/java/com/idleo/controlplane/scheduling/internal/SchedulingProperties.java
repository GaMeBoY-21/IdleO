package com.idleo.controlplane.scheduling.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Booking settings (ADR 0015).
 *
 * @param offerTtl how long an offer stays open (technical design line 251: 10 seconds)
 * @param maxOffers offers per session before it's NO_HOST_FREE (line 230: 3)
 * @param offerSweepInterval how often expired offers are looked for
 * @param rematchRetryEvery how often a session being matched again after a lost host, with no candidate yet,
 *     is tried again
 * @param rematchWait how long such a session waits for a host before it's NO_HOST_FREE (ADR 0016)
 * @param priceBandDefaults the suggested price range for a GPU memory class, in GB, when too few other hosts have the
 *     same GPU model to suggest one (ADR 0023)
 */
@ConfigurationProperties("scheduling")
@Validated
record SchedulingProperties(
        @NotNull Duration offerTtl,
        @Min(1) @Max(3) int maxOffers,
        @NotNull Duration offerSweepInterval,
        @NotNull Duration rematchRetryEvery,
        @NotNull Duration rematchWait,
        @NotNull Map<Integer, BandDefault> priceBandDefaults) {

    /** A price range, in paise a minute. */
    record BandDefault(@Min(1) int lowPaisePerMin, @Min(1) int highPaisePerMin) {}
}
