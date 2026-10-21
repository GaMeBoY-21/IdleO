package com.idleo.controlplane.billing.internal;

import static com.idleo.controlplane.billing.internal.LedgerEntry.credit;
import static com.idleo.controlplane.billing.internal.LedgerEntry.debit;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.Rupees;
import com.idleo.controlplane.hosts.HostOwners;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What billing holds about a person (ADR 0037, docs/reference/personal-data-map.md): their wallet, every ledger line of it,
 * the credit they were given, and as a host their earnings. Money records are kept by opaque id; erasure only
 * forfeits the free credit left in the wallet, through the ledger like every other movement. Unpaid earnings stop
 * an account being deleted until they're paid out.
 */
@Component
class BillingPersonalData implements PersonalData {

    static final String FORFEIT_REF = "credit_forfeit";

    private final JdbcClient jdbc;
    private final ExportRows rows;
    private final HostOwners owners;
    private final Ledger ledger;
    private final TransactionTemplate transactions;

    BillingPersonalData(
            JdbcClient jdbc, ExportRows rows, HostOwners owners, Ledger ledger, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.owners = owners;
        this.ledger = ledger;
        this.transactions = transactions;
    }

    @Override
    public String module() {
        return "billing";
    }

    private List<UUID> hosts(UUID userId) {
        return owners.ownedBy(userId).stream().map(HostOwners.OwnedHost::id).toList();
    }

    @Override
    public List<Group> summary(UUID userId) {
        var hosts = hosts(userId);
        return List.of(
                lines("money", "ledger lines", Account.RENTER_WALLET, List.of(userId)),
                lines("hosting", "earnings lines", Account.HOST_PAYABLE, hosts));
    }

    private Group lines(String group, String label, Account account, List<UUID> owners) {
        if (owners.isEmpty()) {
            return new Group(group, label, 0, null, null);
        }
        return jdbc.sql("""
                        select count(*), min(created_at), max(created_at) from billing.ledger_entries
                        where account = :account and owner_id in (:owners)
                        """)
                .param("account", account.dbValue)
                .param("owners", owners)
                .query((rs, n) -> new Group(
                        group, label, rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .single();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public List<Part> export(UUID userId) {
        var params = Map.of("user", userId, "wallet", Account.RENTER_WALLET.dbValue);
        var hosts = hosts(userId);
        return List.of(
                new Part(
                        "money",
                        "wallet",
                        rows.one(
                                jdbc,
                                "select balance_paise, reserved_paise, updated_at from billing.wallets"
                                        + " where user_id = :user",
                                params)),
                new Part("money", "ledger", rows.list(jdbc, """
                                select created_at, ref_type as purpose, direction, amount_paise, ref_id, txn_id
                                from billing.ledger_entries where account = :wallet and owner_id = :user
                                order by created_at, id
                                """, params)),
                new Part(
                        "money",
                        "creditGrants",
                        rows.list(
                                jdbc,
                                "select amount_paise, reason, created_at from billing.credit_grants"
                                        + " where user_id = :user order by created_at",
                                params)),
                new Part(
                        "hosting",
                        "earnings",
                        hosts.isEmpty()
                                ? List.of()
                                : rows.list(
                                        jdbc, """
                                        select created_at, owner_id as host_id, ref_type as purpose, direction,
                                               amount_paise, ref_id, txn_id
                                        from billing.ledger_entries where account = :payable and owner_id in (:hosts)
                                        order by created_at, id
                                        """, Map.of("payable", Account.HOST_PAYABLE.dbValue, "hosts", hosts))));
    }

    /** What the person's machines have earned and not been paid: credits less debits of their payable accounts. */
    private long unpaid(UUID userId) {
        var hosts = hosts(userId);
        if (hosts.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        select coalesce(sum(case when direction = 'credit' then amount_paise else -amount_paise end), 0)
                        from billing.ledger_entries where account = :payable and owner_id in (:hosts)
                        """)
                .param("payable", Account.HOST_PAYABLE.dbValue)
                .param("hosts", hosts)
                .query(Long.class)
                .single();
    }

    @Override
    public List<Blocker> blockers(UUID userId) {
        var unpaid = unpaid(userId);
        return unpaid <= 0
                ? List.of()
                : List.of(new Blocker(
                        "unpaid-earnings",
                        "Your machines have earned " + Rupees.format(unpaid) + " that hasn't been paid out yet. "
                                + "It's paid to you before your account is deleted: contact us and we'll arrange it.",
                        unpaid));
    }

    /** The wallet's credit not held for a session: what deleting the account forfeits. */
    private long available(UUID userId) {
        return jdbc.sql("select balance_paise - reserved_paise from billing.wallets where user_id = :user")
                .param("user", userId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    @Override
    public List<Loss> losses(UUID userId) {
        var credit = available(userId);
        return credit <= 0
                ? List.of()
                : List.of(new Loss(
                        "credit-forfeited",
                        "Your " + Rupees.format(credit) + " of free credit can't be paid out, so it's forfeited.",
                        credit));
    }

    /**
     * Forfeits the free credit left (ADR 0037): one balanced transaction back to promotions, its txn_id derived from the
     * person, so running it again posts nothing. Paid credit, once there is any, goes back to where it came from here.
     */
    @Override
    public void erase(UUID userId, ErasureStep step) {
        if (step != ErasureStep.DE_IDENTIFY) {
            return;
        }
        transactions.executeWithoutResult(status -> {
            var wallet = ledger.lockWallet(userId);
            var credit = wallet.available();
            if (!credit.isPositive()) {
                return;
            }
            ledger.post(new LedgerTransaction(
                    UUID.nameUUIDFromBytes((FORFEIT_REF + ":" + userId).getBytes(StandardCharsets.UTF_8)),
                    FORFEIT_REF,
                    userId,
                    List.of(debit(Account.RENTER_WALLET, userId, credit), credit(Account.PROMOTIONS, null, credit))));
        });
    }
}
