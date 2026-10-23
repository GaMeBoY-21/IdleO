package com.idleo.controlplane;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMax;
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
        @NotNull @Valid Notices notices,
        @NotNull @Valid Support support,
        @NotNull @Valid Grievance grievance,
        @NotNull @Valid Credit credit,
        @NotNull @Valid TopUps topUps) {

    /**
     * The limits on buying credit (ADR 0039), in paise. An account younger than {@code newAccountAge} has the {@code
     * newAccount…} limits instead, and none may hold more than {@code maxBoughtBalancePaise} of bought credit.
     */
    public record TopUps(
            @Min(100) long minPaise,
            @Min(100) long maxPaise,
            @Min(100) long perDayPaise,
            @Min(100) long maxBoughtBalancePaise,
            @NotNull Duration newAccountAge,
            @Min(100) long newAccountMaxPaise,
            @Min(100) long newAccountPerDayPaise) {}

    /**
     * What IdleO credit is (ADR 0039): closed-loop, spent only on IdleO, never withdrawn or transferred; bought credit
     * never expires, free credit (grants and promotions, never a give-back) expires after {@code freeExpiresAfter}.
     */
    public record Credit(@NotNull Duration freeExpiresAfter) {}

    /** How long the records the law sets a period for are kept. */
    public record Retention(
            @NotNull Duration securityEvents,
            @NotNull Duration moneyRecords,
            @NotNull Duration auditLog,
            @NotNull Duration backups,
            @NotNull Duration supportTickets,
            @NotNull Duration behaviour,
            @NotNull Duration abuseReviews) {}

    /** How soon a privacy request or a grievance is answered: at most 90 days, whatever the setting. */
    public record Support(@NotNull @DurationMax(days = 90) Duration responseDue) {}

    /** The grievance officer, as the Contact and Privacy pages name them. */
    public record Grievance(
            @NotBlank String name,
            @NotBlank String email,
            @NotBlank String postalAddress) {}

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
