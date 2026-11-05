package com.idleo.controlplane.console.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long the console's search box and timeline wait for each module (ADR 0052), and how many hits the search takes
 * from each.
 */
@ConfigurationProperties("console.search")
@Validated
record ConsoleSearchProperties(
        @DefaultValue("500ms") @NotNull Duration providerTimeout,
        @DefaultValue("5") int hitsPerType) {}
