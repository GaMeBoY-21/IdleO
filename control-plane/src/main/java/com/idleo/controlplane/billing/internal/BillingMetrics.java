package com.idleo.controlplane.billing.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Billing's meters (ADR 0036), all from the ledger, the one place money moves:
 *
 * <ul>
 *   <li>{@code billing.ledger.paise}: paise posted, by what caused it ({@code ref_type}: {@code session} is a
 *       charge, {@code session_refund} and {@code dispute_refund} give money back, {@code credit_grant} and
 *       {@code credit_grant_reversal} are free credit), the account and its direction. What renters were charged is
 *       {@code ref_type=session, account=renter_wallet, direction=debit}.
 *   <li>{@code billing.ledger.transactions}: transactions posted, by {@code ref_type}; credit grants are
 *       {@code ref_type=credit_grant}.
 *   <li>{@code billing.reserved.paise}: credit held for sessions now, across every wallet.
 *   <li>{@code billing.clawbacks} and {@code billing.clawbacks.paise}: refunds of earnings already paid out (ADR 0040),
 *       by {@code outcome}: {@code covered} by the owner's balance, {@code owed} back from later earnings, or {@code
 *       waived} by an admin.
 * </ul>
 *
 * Counted once each transaction has committed, so a meter never says more than the ledger does.
 */
@Component
class BillingMetrics {

    private final MeterRegistry registry;

    BillingMetrics(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        Gauge.builder(
                        "billing.reserved.paise",
                        () -> jdbc.sql("select coalesce(sum(reserved_paise), 0) from billing.wallets")
                                .query(Long.class)
                                .single())
                .description("Credit held for sessions now, in paise, across every wallet")
                .register(registry);
    }

    void clawedBack(long amountPaise, long owedPaise) {
        com.idleo.controlplane.AfterCommit.run(() -> {
            var outcome = owedPaise > 0 ? "owed" : "covered";
            registry.counter("billing.clawbacks", "outcome", outcome).increment();
            registry.counter("billing.clawbacks.paise", "outcome", outcome).increment(amountPaise);
        });
    }

    void clawbackWaived(long amountPaise) {
        com.idleo.controlplane.AfterCommit.run(() -> {
            registry.counter("billing.clawbacks", "outcome", "waived").increment();
            registry.counter("billing.clawbacks.paise", "outcome", "waived").increment(amountPaise);
        });
    }

    void posted(LedgerTransaction transaction) {
        registry.counter("billing.ledger.transactions", "ref_type", transaction.refType())
                .increment();
        for (var entry : transaction.entries()) {
            registry.counter(
                            "billing.ledger.paise",
                            "ref_type",
                            transaction.refType(),
                            "account",
                            entry.account().dbValue,
                            "direction",
                            entry.direction().dbValue)
                    .increment(entry.amount().value());
        }
    }
}
