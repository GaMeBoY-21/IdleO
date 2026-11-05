package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ConsoleSeed;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Billing's made-up top-ups (ADR 0052): the rows the console reads, and no ledger behind them, since no money moved. So
 * this data is for measuring the console and nothing else, which is why it's never in production.
 */
@Component
class BillingSeed implements ConsoleSeed {

    private static final long EVERY_MS = 180_000L;

    private final JdbcClient jdbc;

    BillingSeed(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void seed(Sizes sizes) {
        jdbc.sql("""
                        insert into billing.topups
                            (id, user_id, amount_paise, currency, receipt, order_id, status, payment_id, method,
                             credited_by, txn_id, created_at, updated_at, paid_at)
                        select %s, %s,
                               (array[10000, 50000, 100000, 200000, 500000])[1 + n %% 5],
                               'INR', 'seed-' || n, 'order_seed' || n, 'paid', 'pay_seed' || n, 'upi', 'webhook',
                               %s, %s, %s, %s
                        from (select generate_series(0, :payments - 1) as n, %s as owner) paid
                        on conflict do nothing
                        """.formatted(
                                ConsoleSeed.id("topup", "n", EVERY_MS),
                                ConsoleSeed.userId("owner"),
                                ConsoleSeed.id("txn", "n", EVERY_MS),
                                ConsoleSeed.at("n", EVERY_MS),
                                ConsoleSeed.at("n", EVERY_MS),
                                ConsoleSeed.at("n", EVERY_MS),
                                ConsoleSeed.busyUser(sizes.users())))
                .param("payments", sizes.payments())
                .update();
    }
}
