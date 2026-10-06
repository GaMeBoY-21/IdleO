package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way money moves (ADR 0014): {@link #post} writes a balanced ledger transaction and updates
 * the wallet caches it touches, in the same database transaction. Nothing else writes {@code
 * billing.ledger_entries} or {@code billing.wallets}; a test fails if anything does.
 *
 * <p>The code checks every transaction before it reaches the database, and the database checks it
 * again: a deferred trigger refuses an unbalanced transaction at commit, triggers refuse any change
 * to an entry, and the wallet table refuses a negative balance.
 */
@Component
class Ledger {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;

    Ledger(JdbcClient jdbc, PlatformTransactionManager transactionManager, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
    }

    /** The outcome of a posting: {@code posted} is false when the transaction was already in the ledger. */
    record Posting(UUID txnId, boolean posted, Instant postedAt) {}

    /**
     * Posts a balanced transaction, joining the caller's database transaction if there is one. Posting
     * a {@code txnId} that's already in the ledger with the same entries changes nothing and returns
     * the first posting; with different entries it's refused.
     *
     * @throws IllegalArgumentException if the transaction isn't balanced or breaks an ownership rule
     * @throws IllegalStateException if it would take a wallet negative, which can only be a bug
     */
    Posting post(LedgerTransaction transaction) {
        validate(transaction);
        return Objects.requireNonNull(transactions.execute(status -> write(transaction)));
    }

    /**
     * Locks the renter's wallet for the rest of the caller's database transaction, creating it at zero
     * if this is their first posting, so checks against the balance can't race. Requires a transaction.
     */
    WalletBalance lockWallet(UUID userId) {
        jdbc.sql("""
                        insert into billing.wallets (user_id, balance_paise, reserved_paise, version, updated_at)
                        values (:userId, 0, 0, 0, :now)
                        on conflict (user_id) do nothing
                        """)
                .param("userId", userId)
                .param("now", Timestamp.from(now()))
                .update();
        return jdbc.sql("""
                        select balance_paise, reserved_paise from billing.wallets
                        where user_id = :userId
                        for update
                        """)
                .param("userId", userId)
                .query((rs, n) -> new WalletBalance(
                        userId, Paise.of(rs.getLong("balance_paise")), Paise.of(rs.getLong("reserved_paise"))))
                .single();
    }

    static void validate(LedgerTransaction transaction) {
        var entries = transaction.entries();
        if (entries.size() < 2) {
            throw new IllegalArgumentException("a ledger transaction needs at least two entries");
        }
        if (transaction.refType().isBlank()) {
            throw new IllegalArgumentException("a ledger transaction needs a ref_type");
        }
        var debits = Paise.ZERO;
        var credits = Paise.ZERO;
        var keys = new HashSet<List<Object>>();
        for (var entry : entries) {
            if (!entry.amount().isPositive()) {
                throw new IllegalArgumentException("every entry needs an amount above zero");
            }
            if (entry.account().owned != (entry.ownerId() != null)) {
                throw new IllegalArgumentException(
                        entry.account().dbValue + (entry.account().owned ? " needs an owner" : " can't have an owner"));
            }
            if (!keys.add(Arrays.asList(entry.account(), entry.ownerId()))) {
                throw new IllegalArgumentException("an account and owner appears twice in one transaction");
            }
            if (entry.direction() == Direction.DEBIT) {
                debits = debits.plus(entry.amount());
            } else {
                credits = credits.plus(entry.amount());
            }
        }
        if (!debits.equals(credits)) {
            throw new IllegalArgumentException("unbalanced: debits " + debits + ", credits " + credits);
        }
    }

    private Posting write(LedgerTransaction transaction) {
        // Lock every renter wallet involved, in a fixed order, so concurrent postings for a renter take
        // turns and can't deadlock each other.
        var walletChanges = new TreeMap<UUID, Paise>();
        for (var entry : transaction.entries()) {
            if (entry.account() == Account.RENTER_WALLET) {
                walletChanges.merge(entry.ownerId(), entry.signedForCreditAccount(), Paise::plus);
            }
        }
        var wallets = new ArrayList<WalletBalance>();
        for (var userId : walletChanges.keySet()) {
            wallets.add(lockWallet(userId));
        }

        var now = now();
        int inserted = 0;
        for (var entry : transaction.entries()) {
            inserted += jdbc.sql("""
                            insert into billing.ledger_entries
                                (id, txn_id, account, owner_id, amount_paise, direction, ref_type, ref_id, created_at)
                            values (:id, :txnId, :account, :ownerId, :amount, :direction, :refType, :refId, :now)
                            on conflict on constraint ledger_entries_txn_account_owner_key do nothing
                            """)
                    .param("id", ids.newId())
                    .param("txnId", transaction.txnId())
                    .param("account", entry.account().dbValue)
                    .param("ownerId", entry.ownerId())
                    .param("amount", entry.amount().value())
                    .param("direction", entry.direction().dbValue)
                    .param("refType", transaction.refType())
                    .param("refId", transaction.refId())
                    .param("now", Timestamp.from(now))
                    .update();
        }
        if (inserted == 0) {
            return alreadyPosted(transaction);
        }
        if (inserted != transaction.entries().size()) {
            throw new IllegalStateException(
                    "ledger transaction " + transaction.txnId() + " is already posted with different entries");
        }

        for (var wallet : wallets) {
            var change = walletChanges.get(wallet.userId());
            var balance = wallet.balance().plus(change);
            if (balance.isNegative() || wallet.reserved().isGreaterThan(balance)) {
                throw new IllegalStateException("posting " + transaction.txnId() + " would take wallet "
                        + wallet.userId() + " below zero or below its reservation: a bug");
            }
            jdbc.sql("""
                            update billing.wallets
                            set balance_paise = balance_paise + :change, version = version + 1, updated_at = :now
                            where user_id = :userId
                            """)
                    .param("change", change.value())
                    .param("now", Timestamp.from(now))
                    .param("userId", wallet.userId())
                    .update();
        }
        return new Posting(transaction.txnId(), true, now);
    }

    /** The unique key said this txn_id is in: it must be the same transaction, and nothing changes. */
    private Posting alreadyPosted(LedgerTransaction transaction) {
        record Stored(
                Account account,
                UUID ownerId,
                long amount,
                Direction direction,
                String refType,
                UUID refId,
                Instant createdAt) {}
        var stored = jdbc.sql("""
                        select account, owner_id, amount_paise, direction, ref_type, ref_id, created_at
                        from billing.ledger_entries where txn_id = :txnId
                        """)
                .param("txnId", transaction.txnId())
                .query((rs, n) -> new Stored(
                        Account.fromDb(rs.getString("account")),
                        rs.getObject("owner_id", UUID.class),
                        rs.getLong("amount_paise"),
                        Direction.fromDb(rs.getString("direction")),
                        rs.getString("ref_type"),
                        rs.getObject("ref_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        var expected = new HashSet<List<Object>>();
        for (var entry : transaction.entries()) {
            expected.add(Arrays.asList(
                    entry.account(), entry.ownerId(), entry.amount().value(), entry.direction()));
        }
        var actual = new HashSet<List<Object>>();
        for (var entry : stored) {
            actual.add(Arrays.asList(entry.account(), entry.ownerId(), entry.amount(), entry.direction()));
        }
        boolean sameRef = stored.stream()
                .allMatch(entry -> entry.refType().equals(transaction.refType())
                        && entry.refId().equals(transaction.refId()));
        if (!expected.equals(actual) || !sameRef) {
            throw new IllegalStateException(
                    "ledger transaction " + transaction.txnId() + " is already posted with different entries");
        }
        return new Posting(transaction.txnId(), false, stored.getFirst().createdAt());
    }

    /** Now, to the microsecond PostgreSQL stores, so a returned time equals the stored one on every OS. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
