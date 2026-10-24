package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AfterCommit;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Money out (ADR 0040), counted once its transaction commits, with bounded tags only (statuses and outcomes from short
 * lists):
 *
 * <ul>
 *   <li>{@code payouts.status}: payouts reaching each RazorpayX status; PayoutsFailing and PayoutReversed read it;
 *   <li>{@code payouts.paid.paise}: what reached hosts' UPI IDs;
 *   <li>{@code payouts.days-to-paid}: from the oldest earning a payout carried to its money arriving, the host's
 *       number;
 *   <li>{@code payouts.verifications}: PAN and UPI ID checks, by outcome;
 *   <li>{@code payouts.reconciliation.open}: RazorpayX and our payouts disagreeing, which must be 0
 *       (PayoutMismatch);
 *   <li>{@code payouts.balance.short}: 1 when the last balance check found too little for the next run
 *       (PayoutBalanceLow), with {@code payouts.balance.shortfall.paise};
 *   <li>{@code payouts.webhook.signature_failures}.
 * </ul>
 */
@Component
class PayoutsMetrics {

    static final Set<String> STATUSES = Set.of(
            "created", "queued", "pending", "processing", "processed", "failed", "rejected", "cancelled", "reversed");
    private static final Set<String> CHECK_OUTCOMES = Set.of(
            "verified",
            "review",
            "rejected",
            "verified_by_admin",
            "rejected_by_admin",
            "active",
            "invalid",
            "failed",
            "approved_by_admin");

    private final MeterRegistry registry;
    private final DistributionSummary daysToPaid;
    private final AtomicLong openMismatches = new AtomicLong();
    private final AtomicLong balanceShort = new AtomicLong();
    private final AtomicLong shortfall = new AtomicLong();

    PayoutsMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.daysToPaid = DistributionSummary.builder("payouts.days-to-paid")
                .description("Days from the oldest earning a payout carried to its money reaching the host")
                .serviceLevelObjectives(7, 8, 9, 10, 14, 21)
                .register(registry);
        Gauge.builder("payouts.reconciliation.open", openMismatches, AtomicLong::get)
                .description("RazorpayX's payouts and ours disagreeing, not closed yet: must be 0")
                .register(registry);
        Gauge.builder("payouts.balance.short", balanceShort, AtomicLong::get)
                .description("1 when RazorpayX's balance can't cover the next payout run")
                .register(registry);
        Gauge.builder("payouts.balance.shortfall.paise", shortfall, AtomicLong::get)
                .description("How much the RazorpayX balance falls short of the next run, in paise")
                .register(registry);
        // The rare ones start at zero, so the first after a restart is an increase an alert can see.
        for (var status : List.of("processed", "failed", "rejected", "cancelled", "reversed")) {
            registry.counter("payouts.status", "status", status);
        }
        registry.counter("payouts.webhook.signature_failures");
    }

    void status(String status) {
        var tag = STATUSES.contains(status) ? status : "other";
        AfterCommit.run(() -> registry.counter("payouts.status", "status", tag).increment());
    }

    void paid(long netPaise, Duration sinceOldestEarning) {
        AfterCommit.run(() -> {
            registry.counter("payouts.paid.paise").increment(netPaise);
            daysToPaid.record(Math.max(0, sinceOldestEarning.toMinutes()) / (24.0 * 60));
        });
    }

    void panChecked(String outcome) {
        verification("pan", outcome);
    }

    void upiChecked(String outcome) {
        verification("upi", outcome);
    }

    private void verification(String kind, String outcome) {
        var tag = CHECK_OUTCOMES.contains(outcome) ? outcome : "other";
        AfterCommit.run(() -> registry.counter("payouts.verifications", "kind", kind, "outcome", tag)
                .increment());
    }

    void openMismatches(long count) {
        openMismatches.set(count);
    }

    void balance(boolean isShort, long shortfallPaise) {
        balanceShort.set(isShort ? 1 : 0);
        shortfall.set(Math.max(0, shortfallPaise));
    }

    void signatureFailed() {
        registry.counter("payouts.webhook.signature_failures").increment();
    }
}
