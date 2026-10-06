package com.idleo.controlplane.billing.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly proof that the wallet cache equals the ledger (technical design line 405, ADR 0014): for
 * every renter, the cached balance must equal their {@code renter_wallet} credits minus debits, and the
 * whole ledger must balance. One control-plane instance runs it at a time, under a PostgreSQL advisory
 * lock; the others skip. Mismatches are logged (user id and both values, nothing more) and counted in
 * {@code billing.reconciliation.mismatches}; {@code billing.reconciliation.last_success} is when the
 * last complete run finished.
 */
@Component
class WalletReconciliation {

    /** The advisory lock key: "billrecn" in ASCII. */
    static final long LOCK_KEY = 0x62696c6c7265636eL;

    private static final Logger log = LoggerFactory.getLogger(WalletReconciliation.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;
    private final Clock clock;
    private final Counter mismatches;
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    WalletReconciliation(
            JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.snapshot = new TransactionTemplate(transactionManager);
        // One consistent view of wallets and ledger, while postings carry on.
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.clock = clock;
        this.mismatches = Counter.builder("billing.reconciliation.mismatches")
                .description("Wallets whose cached balance differs from the ledger, plus an unbalanced ledger")
                .register(registry);
        Gauge.builder("billing.reconciliation.last_success", lastSuccessEpochSeconds, AtomicLong::get)
                .description("When the last complete reconciliation finished, in Unix seconds")
                .register(registry);
    }

    record Mismatch(UUID userId, long walletPaise, long ledgerPaise) {}

    /** {@code ran} is false when another instance held the lock. */
    record Result(boolean ran, List<Mismatch> mismatches, long ledgerDebitsPaise, long ledgerCreditsPaise) {

        boolean ledgerBalanced() {
            return ledgerDebitsPaise == ledgerCreditsPaise;
        }
    }

    @Scheduled(cron = "${billing.reconciliation.cron}", zone = "${billing.reconciliation.zone}")
    void nightly() {
        run();
    }

    Result run() {
        var result = Objects.requireNonNull(snapshot.execute(status -> {
            Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)")
                    .param("key", LOCK_KEY)
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(locked)) {
                return new Result(false, List.of(), 0, 0);
            }
            var walletMismatches = jdbc.sql("""
                            with ledger as (
                                select owner_id,
                                       sum(case direction when 'credit' then amount_paise else -amount_paise end) as paise
                                from billing.ledger_entries
                                where account = 'renter_wallet'
                                group by owner_id
                            )
                            select coalesce(w.user_id, l.owner_id) as user_id,
                                   coalesce(w.balance_paise, 0) as wallet_paise,
                                   coalesce(l.paise, 0) as ledger_paise
                            from billing.wallets w
                            full join ledger l on l.owner_id = w.user_id
                            where coalesce(w.balance_paise, 0) <> coalesce(l.paise, 0)
                            order by 1
                            """)
                    .query((rs, n) -> new Mismatch(
                            rs.getObject("user_id", UUID.class),
                            rs.getLong("wallet_paise"),
                            rs.getLong("ledger_paise")))
                    .list();
            var totals = jdbc.sql("""
                            select coalesce(sum(amount_paise) filter (where direction = 'debit'), 0) as debits,
                                   coalesce(sum(amount_paise) filter (where direction = 'credit'), 0) as credits
                            from billing.ledger_entries
                            """)
                    .query((rs, n) -> new long[] {rs.getLong("debits"), rs.getLong("credits")})
                    .single();
            return new Result(true, walletMismatches, totals[0], totals[1]);
        }));
        if (!result.ran()) {
            log.info("Wallet reconciliation skipped: another instance is running it");
            return result;
        }
        for (var mismatch : result.mismatches()) {
            log.error(
                    "Wallet {} disagrees with the ledger: wallet {} paise, ledger {} paise",
                    mismatch.userId(),
                    mismatch.walletPaise(),
                    mismatch.ledgerPaise());
        }
        int problems = result.mismatches().size();
        if (!result.ledgerBalanced()) {
            log.error(
                    "The ledger doesn't balance: debits {} paise, credits {} paise",
                    result.ledgerDebitsPaise(),
                    result.ledgerCreditsPaise());
            problems++;
        }
        mismatches.increment(problems);
        lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
        log.info("Wallet reconciliation finished: {} mismatches", problems);
        return result;
    }
}
