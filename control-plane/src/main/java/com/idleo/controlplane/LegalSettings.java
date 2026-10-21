package com.idleo.controlplane;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every period and text the law decides, from the one {@code legal:} block of application.yaml (ADR 0037). Each is
 * marked LEGAL(confirm) there: a proposal until a lawyer confirms it.
 */
@ConfigurationProperties("legal")
@Validated
public record LegalSettings(
        @NotNull @Valid Retention retention,
        @NotNull @Valid Erasure erasure,
        @NotNull @Valid Notices notices) {

    /** How long the records the law sets a period for are kept. */
    public record Retention(
            @NotNull Duration securityEvents,
            @NotNull Duration moneyRecords,
            @NotNull Duration auditLog,
            @NotNull Duration backups) {}

    /** How long a confirmed deletion waits, for the person to change their mind. */
    public record Erasure(@NotNull Duration waitingPeriod) {}

    /** The Terms and privacy notice in force, by version, and the age a person must confirm. */
    public record Notices(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,40}")
            String termsVersion,

            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,40}")
            String privacyVersion,

            @Min(18) int minimumAge) {}
}
