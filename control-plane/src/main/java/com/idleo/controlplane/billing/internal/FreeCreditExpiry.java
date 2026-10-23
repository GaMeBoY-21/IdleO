package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AfterCommit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Free credit expires (ADR 0039): once a day, every free lot past its {@code expires_at} gives up what's left in it,
 * one balanced transaction a lot, from {@code renter_wallet} to {@code free_credit_expired}. Never what a running
 * session holds: a lot expires only as far as the wallet's available balance allows, and the rest the next day, once
 * the hold is gone. Bought credit and give-backs have no expiry, so never come here. One control-plane instance runs
 * it at a time, under a PostgreSQL advisory lock; {@code billing.free_credit.expired} counts the paise.
 */
@Component
class FreeCreditExpiry {

    /** The advisory lock key: "freexpir" in ASCII. */
    static final long LOCK_KEY = 0x6672656578706972L;

    static final String EXPIRY_REF = "free_credit_expiry";

    private static final Logger log = LoggerFactory.getLogger(FreeCreditExpiry.class);

    private final Ledger ledger;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Counter expired;

    FreeCreditExpiry(
            Ledger ledger,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry registry) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.expired = Counter.builder("billing.free_credit.expired")
                .description("Free credit that expired unspent, in paise")
                .baseUnit("paise")
                .register(registry);
    }

    @Scheduled(cron = "${billing.free-credit-expiry.cron}", zone = "${billing.reconciliation.zone}")
    void daily() {
        expire(clock.instant());
    }

    /** Expires what's due at {@code now}; how many paise it expired. */
    long expire(Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> {
            Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)")
                    .param("key", LOCK_KEY)
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(locked)) {
                return 0L;
            }
            var due = jdbc.sql("""
                            select id, user_id from billing.credit_lots
                            where kind = 'free' and remaining_paise > 0 and expires_at <= :now
                            order by expires_at, id
                            """)
                    .param("now", Timestamp.from(now))
                    .query((rs, n) -> new UUID[] {rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)})
                    .list();
            long total = 0;
            for (var lotAndUser : due) {
                total += expireLot(lotAndUser[0], lotAndUser[1]);
            }
            long paise = total;
            if (paise > 0) {
                AfterCommit.run(() -> expired.increment(paise));
                log.info("Expired {} paise of free credit from {} lots", paise, due.size());
            }
            return paise;
        }));
    }

    private long expireLot(UUID lotId, UUID userId) {
        var wallet = ledger.lockWallet(userId);
        var lot = ledger.lot(lotId);
        var amount = lot.remaining().isGreaterThan(wallet.available()) ? wallet.available() : lot.remaining();
        if (!amount.isPositive()) {
            return 0;
        }
        // Named by the lot and what it held, so a second run the same day posts nothing, and a lot that expired only in
        // part (a hold was on it) expires the rest under a name of its own.
        var txnId = UUID.nameUUIDFromBytes(
                (EXPIRY_REF + ":" + lotId + ":" + lot.remaining().value()).getBytes(StandardCharsets.UTF_8));
        ledger.post(new LedgerTransaction(
                txnId,
                EXPIRY_REF,
                lotId,
                List.of(
                        LedgerEntry.debitLot(Account.RENTER_WALLET, userId, amount, lotId),
                        LedgerEntry.credit(Account.FREE_CREDIT_EXPIRED, null, amount))));
        return amount.value();
    }
}
