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
        @NotNull @Valid TopUps topUps,
        @NotNull @Valid Invoicing invoicing) {

    /**
     * Invoices and GST (ADR 0039), every value the CA's to confirm. With no {@code gstin} we aren't registered: no GST
     * is charged and every document says "Not a tax invoice: GST registration pending". {@code pricesIncludeGst} says
     * whether the prices renters see have GST in them (a host's listing price is always before GST). {@code mode} is
     * {@code per_topup} (a tax invoice for each top-up, GST owed when it's paid) or {@code monthly_usage} (a receipt for
     * each top-up, and a monthly tax invoice for the credit used). {@code tcs} is the e-commerce operator's tax
     * collected at source, not built: the prod profile refuses it on until it is.
     */
    public record Invoicing(
            String legalName,
            String address,
            String gstin,
            String stateCode,
            String sac,
            @Min(0) @jakarta.validation.constraints.Max(28) int gstRatePercent,
            boolean pricesIncludeGst,
            @NotNull InvoiceMode mode,
            boolean tcs,
            @NotBlank @Pattern(regexp = "[A-Z]{1,4}") String numberPrefix) {

        /** Whether we're registered for GST, so a tax invoice can be issued and GST charged. */
        public boolean registered() {
            return gstin != null && !gstin.isBlank();
        }
    }

    public enum InvoiceMode {
        PER_TOPUP,
        MONTHLY_USAGE
    }

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
