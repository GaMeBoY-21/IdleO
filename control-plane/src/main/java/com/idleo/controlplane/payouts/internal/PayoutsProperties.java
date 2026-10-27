package com.idleo.controlplane.payouts.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The product's own payout settings (ADR 0040); what the law decides is {@code legal.payouts} and {@code legal.tds}.
 *
 * @param minimumPaise the least a weekly payout pays; less waits for the next week
 * @param changeHold how long after a change of UPI ID no payout is made
 * @param nameMatchThreshold the least name-match score RazorpayX's check may give before an admin looks
 * @param checksPerDay UPI ID checks one person may make in 24 hours (each costs a penny drop live)
 * @param checkValidFor how long a check may wait for the host to confirm it
 * @param sendEvery how often payouts RazorpayX hasn't accepted yet are sent again
 * @param pollAfter how long a payout may go without news before RazorpayX is asked
 * @param pollEvery how often that's looked for
 */
@ConfigurationProperties("payouts")
@Validated
record PayoutsProperties(
        @Min(100) long minimumPaise,
        @NotNull Duration changeHold,
        @Min(0) @Max(100) int nameMatchThreshold,
        @Min(1) int checksPerDay,
        @NotNull Duration checkValidFor,
        @NotNull Duration sendEvery,
        @NotNull Duration pollAfter,
        @NotNull Duration pollEvery,
        @NotNull @Valid Fees fees,
        @NotNull @Valid Pan pan) {

    /**
     * Who pays RazorpayX's charge for a payout: {@code idleo} (the default, the product owner's business decision) or {@code host},
     * who then has {@code hostFeePaise} taken from each payout.
     */
    record Fees(@NotNull PaidBy paidBy, @Min(0) long hostFeePaise) {}

    enum PaidBy {
        IDLEO,
        HOST
    }

    /**
     * PANs: sealed under {@code key} (32 bytes in base64, or {@code file:} a file holding it); checked online by {@code
     * verifier}: {@code manual} (no provider yet: an admin confirms each) or {@code fake} (the tests and the local stack;
     * the prod profile refuses it).
     */
    record Pan(
            @org.jspecify.annotations.Nullable String key,
            @NotNull Verifier verifier) {}

    enum Verifier {
        MANUAL,
        FAKE
    }
}
