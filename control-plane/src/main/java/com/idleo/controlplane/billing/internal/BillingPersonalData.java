package com.idleo.controlplane.billing.internal;

import static com.idleo.controlplane.billing.internal.LedgerEntry.credit;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.Paise;
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
 * forfeits the free credit left in the wallet, through the ledger like every other movement. Unpaid earnings are paid
 * out before the account goes, by the payouts module (ADR 0040).
 */
@Component
class BillingPersonalData implements PersonalData {

    static final String FORFEIT_REF = "credit_forfeit";

    private final JdbcClient jdbc;
    private final ExportRows rows;
    private final HostOwners owners;
    private final Ledger ledger;
    private final TransactionTemplate transactions;

    private final CreditRefunds refunds;

    BillingPersonalData(
            JdbcClient jdbc,
            ExportRows rows,
            HostOwners owners,
            Ledger ledger,
            TransactionTemplate transactions,
            @org.springframework.context.annotation.Lazy CreditRefunds refunds) {
        this.refunds = refunds;
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
                lines(
                        "money",
                        "ledger lines",
                        List.of(Account.RENTER_WALLET, Account.RENTER_PURCHASED),
                        List.of(userId)),
                lines("hosting", "earnings lines", List.of(Account.HOST_PAYABLE), hosts));
    }

    private Group lines(String group, String label, List<Account> accounts, List<UUID> owners) {
        if (owners.isEmpty()) {
            return new Group(group, label, 0, null, null);
        }
        return jdbc.sql("""
                        select count(*), min(created_at), max(created_at) from billing.ledger_entries
                        where account in (:accounts) and owner_id in (:owners)
                        """)
                .param(
                        "accounts",
                        accounts.stream().map(account -> account.dbValue).toList())
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
        var params = Map.of("user", userId);
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
                                select created_at, account, ref_type as purpose, direction, amount_paise, ref_id,
                                       txn_id, lot_id
                                from billing.ledger_entries
                                where account in ('renter_wallet', 'renter_purchased', 'refunds_pending',
                                                  'disputes_frozen')
                                  and owner_id = :user
                                order by created_at, id
                                """, params)),
                new Part("money", "topUps", rows.list(jdbc, """
                                select id, amount_paise, currency, status, order_id, payment_id, method, card_network,
                                       card_last4, failure_reason, created_at, paid_at
                                from billing.topups where user_id = :user order by created_at, id
                                """, params)),
                new Part(
                        "money",
                        "billingDetails",
                        rows.one(
                                jdbc,
                                "select state_code, gstin, business_name, updated_at from billing.billing_details"
                                        + " where user_id = :user",
                                params)),
                new Part("money", "invoices", rows.list(jdbc, """
                                select number, kind, taxable_paise, cgst_paise, sgst_paise, igst_paise, total_paise,
                                       issued_at, document
                                from billing.invoices where user_id = :user order by issued_at, id
                                """, params)),
                new Part("money", "refunds", rows.list(jdbc, """
                                select r.id, r.cause, r.status, r.requested_paise, r.approved_paise, r.note,
                                       r.ticket_number, r.decision, r.requested_at, r.finished_at
                                from billing.credit_refunds r where r.user_id = :user order by r.requested_at
                                """, params)),
                new Part("money", "chargebacks", rows.list(jdbc, """
                                select dispute_id, payment_id, amount_paise, currency, reason_code, status, respond_by,
                                       frozen_paise, lost_paise, released_paise, outcome, opened_at, settled_at
                                from billing.chargebacks where user_id = :user order by received_at
                                """, params)),
                new Part("money", "creditLots", rows.list(jdbc, """
                                select id, kind, source_type, amount_paise, remaining_paise, expires_at, created_at
                                from billing.credit_lots where user_id = :user order by created_at, id
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

    @Override
    public List<Blocker> blockers(UUID userId) {
        var blockers = new java.util.ArrayList<Blocker>(refunds.blockers(userId));
        long disputed = jdbc.sql("""
                        select coalesce(sum(amount_paise), 0) from billing.chargebacks
                        where user_id = :user and outcome is null
                        """).param("user", userId).query(Long.class).single();
        if (disputed > 0) {
            blockers.add(new Blocker(
                    "chargeback-open",
                    "Your bank is disputing " + Rupees.format(disputed)
                            + " you paid us. The account can be deleted once" + " the dispute is settled.",
                    disputed));
        }
        // Earnings not paid out are the payouts module's: a final payout before the account goes (ADR 0040).
        return blockers;
    }

    /** Bought credit goes back to where it was paid from before the account goes (ADR 0039). */
    @Override
    public void beforeErasure(UUID userId) {
        refunds.forDeletion(userId);
    }

    /** The free credit left in the person's lots (ADR 0039): what deleting the account forfeits. */
    private long available(UUID userId) {
        return jdbc.sql("""
                        select coalesce(sum(remaining_paise), 0) from billing.credit_lots
                        where user_id = :user and kind = 'free'
                        """).param("user", userId).query(Long.class).single();
    }

    @Override
    public List<Loss> losses(UUID userId) {
        var credit = available(userId);
        return credit <= 0
                ? List.of()
                : List.of(new Loss(
                        "credit-forfeited",
                        "Your " + Rupees.format(credit) + " of free credit can't be paid out, so it's forfeited. "
                                + "Credit you bought is refunded to where you paid from first.",
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
        // The top-ups stay as money records, by Razorpay's ids; what Razorpay said of the card goes.
        jdbc.sql("update billing.topups set card_network = null, card_last4 = null where user_id = :user")
                .param("user", userId)
                .update();
        jdbc.sql("delete from billing.topup_failures where user_id = :user")
                .param("user", userId)
                .update();
        // Invoices stay whole: the law keeps them (legal.retention.money-records). Billing details go; a refund's
        // own words go, its amounts and Razorpay's ids stay.
        jdbc.sql("delete from billing.billing_details where user_id = :user")
                .param("user", userId)
                .update();
        jdbc.sql("update billing.credit_refunds set note = null where user_id = :user")
                .param("user", userId)
                .update();
        jdbc.sql("delete from billing.idempotency_keys where user_id = :user")
                .param("user", userId)
                .update();
        transactions.executeWithoutResult(status -> {
            ledger.lockWallet(userId);
            var free = ledger.spendable(userId).stream()
                    .filter(lot -> lot.kind() == CreditLot.Kind.FREE)
                    .toList();
            var credit = free.stream().map(CreditLot::remaining).reduce(Paise.ZERO, Paise::plus);
            if (!credit.isPositive()) {
                return;
            }
            var entries = new java.util.ArrayList<>(Ledger.drawFrom(userId, free, credit));
            entries.add(credit(Account.PROMOTIONS, null, credit));
            ledger.post(new LedgerTransaction(
                    UUID.nameUUIDFromBytes((FORFEIT_REF + ":" + userId).getBytes(StandardCharsets.UTF_8)),
                    FORFEIT_REF,
                    userId,
                    entries));
        });
    }
}
