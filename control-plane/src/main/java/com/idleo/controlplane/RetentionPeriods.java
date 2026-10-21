package com.idleo.controlplane;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long the product keeps what the law sets no period for (ADR 0037, docs/reference/personal-data-map.md), by retention
 * rule: {@code retention.periods.<rule>} in application.yaml, the one place; the law's periods are {@link
 * LegalSettings}. A rule asks for its own; one that isn't set fails at start, not at its first run.
 */
@ConfigurationProperties("retention")
@Validated
public record RetentionPeriods(@NotNull Map<String, Duration> periods) {

    /** The rule's period. */
    public Duration of(String rule) {
        var period = periods.get(rule);
        if (period == null) {
            throw new IllegalStateException("retention.periods." + rule + " isn't set");
        }
        return period;
    }
}
