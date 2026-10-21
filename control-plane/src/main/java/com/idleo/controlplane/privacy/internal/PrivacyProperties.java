package com.idleo.controlplane.privacy.internal;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The privacy module's own timings (ADR 0037); the waiting period the law decides is {@code legal.erasure.*}.
 *
 * @param exportKeep how long a made export is kept, and its links to files work
 * @param exportEvery how often a person may ask for an export
 * @param exportSweepEvery how often the job looks for exports to make
 * @param confirmLinkTtl how long the email link that confirms a deletion works
 * @param reminderBefore how long before the deletion its reminder goes
 * @param erasureSweepEvery how often the job looks for accounts due to be erased, and reminders due
 */
@ConfigurationProperties("privacy")
@Validated
record PrivacyProperties(
        @NotNull Duration exportKeep,
        @NotNull Duration exportEvery,
        @NotNull Duration exportSweepEvery,
        @NotNull Duration confirmLinkTtl,
        @NotNull Duration reminderBefore,
        @NotNull Duration erasureSweepEvery) {}
