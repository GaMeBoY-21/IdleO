package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Money coming in (ADR 0039), counted once its transaction commits, with bounded tags only: the payment method from
 * Razorpay's short list (anything else is {@code other}), and where a bad signature came from. {@code
 * billing.topups.seconds-to-credit} is the number that matters: from Razorpay's payment to the credit in the wallet.
 */
@Component
class PaymentMetrics {

    /** Razorpay's payment methods; anything else is counted as {@code other}, so it can't add series. */
    private static final Set<String> METHODS = Set.of("upi", "card", "netbanking", "wallet", "emi", "paylater");

    private final MeterRegistry registry;
    private final Timer secondsToCredit;

    PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.secondsToCredit = Timer.builder("billing.topups.seconds-to-credit")
                .description("From the payment at Razorpay to its credit in the renter's wallet")
                .register(registry);
    }

    static String method(@Nullable String method) {
        return method != null && METHODS.contains(method) ? method : "other";
    }

    void started() {
        AfterCommit.run(() -> registry.counter("billing.topups.started").increment());
    }

    void paid(@Nullable String method, Duration sincePayment) {
        AfterCommit.run(() -> {
            registry.counter("billing.topups.paid", "method", method(method)).increment();
            secondsToCredit.record(sincePayment.isNegative() ? Duration.ZERO : sincePayment);
        });
    }

    void failed(@Nullable String method) {
        AfterCommit.run(() -> registry.counter("billing.topups.failed", "method", method(method))
                .increment());
    }

    /** One payment's part of a refund of bought credit, processed by Razorpay. */
    void refunded() {
        AfterCommit.run(() -> registry.counter("billing.refunds.processed").increment());
    }

    /** One payment's part of a refund that failed, its credit back with the renter. */
    void refundFailed() {
        AfterCommit.run(() -> registry.counter("billing.refunds.failed").increment());
    }

    /** A payment that came back for another order, amount or currency than its top-up's. */
    void mismatched() {
        registry.counter("billing.topups.mismatched").increment();
    }

    /** A signature that didn't check out: {@code checkout} (a payment's) or {@code webhook}. */
    void signatureFailed(String where) {
        registry.counter("billing.payments.signature_failures", "source", where).increment();
    }
}
