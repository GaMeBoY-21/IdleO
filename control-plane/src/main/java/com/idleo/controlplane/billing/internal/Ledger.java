package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AfterCommit;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.HostEarningsChanged;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one way money moves (ADR 0014): {@link #post} writes a balanced ledger transaction and updates
 * the wallet caches and credit lots it touches, in the same database transaction. Nothing else writes {@code
 * billing.ledger_entries}, {@code billing.wallets} or {@code billing.credit_lots}; a test fails if anything does.
 *
 * <p>A renter's credit is kept in lots (ADR 0039): every entry on {@code renter_wallet} (free) or {@code
 * renter_purchased} (bought) names its lot, and {@link #draw} takes a charge from them in the spending order: free
 * credit first, the soonest to expire first, then bought credit, the oldest first.
 *
 * <p>The code checks every transaction before it reaches the database, and the database checks it
 * again: a deferred trigger refuses an unbalanced transaction at commit, triggers refuse any change
 * to an entry, and the wallet table refuses a negative balance.
 *
 * <p>A posting that touches hosts' earnings announces it ({@link HostEarningsChanged}) in the same database
 * transaction, so each host's tray shows today's figure again.
 */
@Component
class Ledger {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final BillingMetrics metrics;

    Ledger(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            Clock clock,
            ApplicationEventPublisher events,
            BillingMetrics metrics) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
        this.events = events;
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

    /**
     * Moves the wallet's reserved amount by {@code change}, for {@code CreditReservationService} only.
     * The caller holds the wallet's lock ({@link #lockWallet}); the database refuses a reservation below
     * zero or above the balance.
     */
    void adjustReserved(UUID userId, Paise change) {
        jdbc.sql("""
                        update billing.wallets
                        set reserved_paise = reserved_paise + :change, version = version + 1, updated_at = :now
                        where user_id = :userId
                        """)
                .param("change", change.value())
                .param("now", Timestamp.from(now()))
                .param("userId", userId)
                .update();
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
            if (entry.account().isRenterCredit() && entry.lotId() == null) {
                throw new IllegalArgumentException(entry.account().dbValue + " needs the lot it draws from or adds to");
            }
            if (!entry.account().lotted() && entry.lotId() != null) {
                throw new IllegalArgumentException(entry.account().dbValue + " has no lots");
            }
            if (!keys.add(Arrays.asList(entry.account(), entry.ownerId(), entry.lotId()))) {
                throw new IllegalArgumentException("an account, owner and lot appears twice in one transaction");
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
            // An owner-less renter entry never gets here past validate(); if it did, the database's
            // ownership check refuses it rather than this map failing first.
            if (entry.account().isRenterCredit() && entry.ownerId() != null) {
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
                                (id, txn_id, account, owner_id, amount_paise, direction, ref_type, ref_id, created_at,
                                 lot_id)
                            values (:id, :txnId, :account, :ownerId, :amount, :direction, :refType, :refId, :now,
                                    :lotId)
                            on conflict on constraint ledger_entries_txn_account_owner_lot_key do nothing
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
                    .param("lotId", entry.lotId())
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
        for (var entry : transaction.entries()) {
            if (entry.account().isRenterCredit()) {
                moveLot(transaction.txnId(), entry);
            }
        }
        var hosts = transaction.entries().stream()
                .filter(entry -> entry.account() == Account.HOST_PAYABLE)
                .map(LedgerEntry::ownerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!hosts.isEmpty()) {
            events.publishEvent(new HostEarningsChanged(hosts));
        }
        AfterCommit.run(() -> metrics.posted(transaction));
        return new Posting(transaction.txnId(), true, now);
    }

    /** The unique key said this txn_id is in: it must be the same transaction, and nothing changes. */
    private Posting alreadyPosted(LedgerTransaction transaction) {
        record Stored(
                Account account,
                UUID ownerId,
                UUID lotId,
                long amount,
                Direction direction,
                String refType,
                UUID refId,
                Instant createdAt) {}
        var stored = jdbc.sql("""
                        select account, owner_id, lot_id, amount_paise, direction, ref_type, ref_id, created_at
                        from billing.ledger_entries where txn_id = :txnId
                        """)
                .param("txnId", transaction.txnId())
                .query((rs, n) -> new Stored(
                        Account.fromDb(rs.getString("account")),
                        rs.getObject("owner_id", UUID.class),
                        rs.getObject("lot_id", UUID.class),
                        rs.getLong("amount_paise"),
                        Direction.fromDb(rs.getString("direction")),
                        rs.getString("ref_type"),
                        rs.getObject("ref_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        var expected = new HashSet<List<Object>>();
        for (var entry : transaction.entries()) {
            expected.add(Arrays.asList(
                    entry.account(),
                    entry.ownerId(),
                    entry.lotId(),
                    entry.amount().value(),
                    entry.direction()));
        }
        var actual = new HashSet<List<Object>>();
        for (var entry : stored) {
            actual.add(
                    Arrays.asList(entry.account(), entry.ownerId(), entry.lotId(), entry.amount(), entry.direction()));
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

    /**
     * Opens a lot of credit for {@code userId}, with nothing in it yet: the posting that credits it fills it. A lot is
     * one per source (a grant, a payment, a give-back), so opening it again returns the same lot. Requires a
     * transaction.
     */
    CreditLot openLot(
            UUID userId,
            CreditLot.Kind kind,
            String sourceType,
            UUID sourceId,
            Paise amount,
            @Nullable Instant expiresAt) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a lot holds more than zero");
        }
        if (kind == CreditLot.Kind.PURCHASED && expiresAt != null) {
            throw new IllegalArgumentException("bought credit never expires");
        }
        jdbc.sql("""
                        insert into billing.credit_lots
                            (id, user_id, kind, source_type, source_id, amount_paise, remaining_paise, expires_at,
                             created_at)
                        values (:id, :userId, :kind, :sourceType, :sourceId, :amount, 0, :expiresAt, :now)
                        on conflict (source_type, source_id) do nothing
                        """)
                .param("id", ids.newId())
                .param("userId", userId)
                .param("kind", kind.dbValue)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .param("amount", amount.value())
                .param("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now()))
                .update();
        var lot = lotOf(sourceType, sourceId).orElseThrow();
        if (!lot.userId().equals(userId) || lot.kind() != kind || !lot.amount().equals(amount)) {
            throw new IllegalStateException("the lot for " + sourceType + " " + sourceId + " is another one");
        }
        return lot;
    }

    /** The lot opened for a source, if there is one. */
    java.util.Optional<CreditLot> lotOf(String sourceType, UUID sourceId) {
        return jdbc.sql("select * from billing.credit_lots where source_type = :sourceType and source_id = :sourceId")
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .query(Ledger::lot)
                .optional();
    }

    /** A lot, by id. */
    CreditLot lot(UUID lotId) {
        return jdbc.sql("select * from billing.credit_lots where id = :id")
                .param("id", lotId)
                .query(Ledger::lot)
                .single();
    }

    /**
     * The renter's lots with credit left, in the order credit is spent (ADR 0039): free first, the soonest to expire
     * first and the ones that never expire last, then bought, the oldest first.
     */
    List<CreditLot> spendable(UUID userId) {
        var lots = new ArrayList<>(
                jdbc.sql("""
                        select * from billing.credit_lots where user_id = :userId and remaining_paise > 0
                        """).param("userId", userId).query(Ledger::lot).list());
        lots.sort(SPENDING_ORDER);
        return lots;
    }

    static final Comparator<CreditLot> SPENDING_ORDER = Comparator.comparing(
                    (CreditLot lot) -> lot.kind().ordinal())
            .thenComparing(CreditLot::expiresAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CreditLot::createdAt)
            .thenComparing(CreditLot::id);

    /**
     * The debits that take {@code amount} from the renter's credit, lot by lot in the spending order. The caller holds
     * the wallet's lock and posts them in the same transaction.
     *
     * @throws IllegalStateException if the lots hold less than {@code amount}: the hold should have prevented it, so a
     *     bug
     */
    List<LedgerEntry> draw(UUID userId, Paise amount) {
        return drawFrom(userId, spendable(userId), amount);
    }

    /** The same, from the given lots only, in their order. */
    static List<LedgerEntry> drawFrom(UUID userId, List<CreditLot> lots, Paise amount) {
        var entries = new ArrayList<LedgerEntry>();
        var left = amount;
        for (var lot : lots) {
            if (!left.isPositive()) {
                break;
            }
            var take = lot.remaining().isGreaterThan(left) ? left : lot.remaining();
            if (take.isPositive()) {
                entries.add(LedgerEntry.debitLot(lot.account(), userId, take, lot.id()));
                left = left.minus(take);
            }
        }
        if (left.isPositive()) {
            throw new IllegalStateException("the credit lots of " + userId + " hold " + left.value()
                    + " paise less than a posting draws from them: a bug");
        }
        return entries;
    }

    /**
     * What postings caused by {@code refId} (of the given kinds) have taken from each of the renter's lots, net of what
     * they gave back: a session's draws, for giving lost time back to the lots it came from.
     */
    java.util.LinkedHashMap<UUID, Paise> drawnBy(UUID userId, UUID refId, List<String> refTypes) {
        var drawn = new java.util.LinkedHashMap<UUID, Paise>();
        jdbc.sql("""
                        select lot_id, sum(case direction when 'debit' then amount_paise else -amount_paise end) as net
                        from billing.ledger_entries
                        where owner_id = :userId and ref_id = :refId and ref_type in (:refTypes)
                          and account in ('renter_wallet', 'renter_purchased') and lot_id is not null
                        group by lot_id
                        """)
                .param("userId", userId)
                .param("refId", refId)
                .param("refTypes", refTypes)
                .query((rs, n) -> {
                    long net = rs.getLong("net");
                    if (net > 0) {
                        drawn.put(rs.getObject("lot_id", UUID.class), Paise.of(net));
                    }
                    return null;
                })
                .list();
        return drawn;
    }

    /** Moves a lot's cache with one of its entries; the database refuses a lot below zero or above what it started at. */
    private void moveLot(UUID txnId, LedgerEntry entry) {
        int moved = jdbc.sql("""
                        update billing.credit_lots set remaining_paise = remaining_paise + :change
                        where id = :lotId and user_id = :userId and kind = :kind
                          and remaining_paise + :change >= 0 and remaining_paise + :change <= amount_paise
                        """)
                .param("change", entry.signedForCreditAccount().value())
                .param("lotId", entry.lotId())
                .param("userId", entry.ownerId())
                .param("kind", entry.account() == Account.RENTER_WALLET ? "free" : "purchased")
                .update();
        if (moved != 1) {
            throw new IllegalStateException("posting " + txnId + " would take lot " + entry.lotId()
                    + " below zero, above what it started at, or isn't the renter's " + entry.account().dbValue
                    + " lot: a bug");
        }
    }

    private static CreditLot lot(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var expires = rs.getTimestamp("expires_at");
        return new CreditLot(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                CreditLot.Kind.fromDb(rs.getString("kind")),
                rs.getString("source_type"),
                rs.getObject("source_id", UUID.class),
                Paise.of(rs.getLong("amount_paise")),
                Paise.of(rs.getLong("remaining_paise")),
                expires == null ? null : expires.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /** Now, to the microsecond PostgreSQL stores, so a returned time equals the stored one on every OS. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
