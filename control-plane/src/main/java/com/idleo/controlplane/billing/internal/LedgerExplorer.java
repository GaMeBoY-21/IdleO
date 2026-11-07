package com.idleo.controlplane.billing.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The ledger as the console reads it (ADR 0055): every account's balance at a moment, for the platform or one owner; the
 * trial balance; and a transaction with every leg and where it came from. It only reads.
 */
@Component
class LedgerExplorer {

    private final JdbcClient jdbc;

    LedgerExplorer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An account's debits and credits up to a moment, and its balance on its normal side. */
    record Balance(Account account, long debitsPaise, long creditsPaise) {

        long balancePaise() {
            return account.raisedByCredits ? creditsPaise - debitsPaise : debitsPaise - creditsPaise;
        }
    }

    /**
     * Every account's balance at {@code at}, from the entries dated up to it; with an owner, only the accounts they can
     * own, and only their entries. An account with no entries is there at zero.
     */
    List<Balance> balances(Instant at, @Nullable UUID owner) {
        var totals = new EnumMap<Account, long[]>(Account.class);
        jdbc.sql("""
                        select account,
                               coalesce(sum(amount_paise) filter (where direction = 'debit'), 0) as debits,
                               coalesce(sum(amount_paise) filter (where direction = 'credit'), 0) as credits
                        from billing.ledger_entries
                        where created_at <= :at and (cast(:owner as uuid) is null or owner_id = cast(:owner as uuid))
                        group by account
                        """).param("at", Timestamp.from(at)).param("owner", owner).query(rs -> {
            totals.put(
                    Account.fromDb(rs.getString("account")), new long[] {rs.getLong("debits"), rs.getLong("credits")});
        });
        var balances = new ArrayList<Balance>();
        for (var account : Account.values()) {
            if (owner != null && !account.owned) {
                continue;
            }
            var found = totals.getOrDefault(account, new long[2]);
            balances.add(new Balance(account, found[0], found[1]));
        }
        return balances;
    }

    /**
     * The trial balance at {@code at}: each account's net balance in the debit or the credit column. The columns are
     * equal when the ledger balances, which the database makes sure of transaction by transaction.
     */
    record TrialBalance(Instant at, List<Balance> accounts, long debitsPaise, long creditsPaise) {

        boolean balanced() {
            return debitsPaise == creditsPaise;
        }
    }

    TrialBalance trialBalance(Instant at) {
        var accounts = balances(at, null);
        long debits = 0;
        long credits = 0;
        for (var balance : accounts) {
            long net = balance.debitsPaise() - balance.creditsPaise();
            if (net > 0) {
                debits += net;
            } else {
                credits -= net;
            }
        }
        return new TrialBalance(at, accounts, debits, credits);
    }

    /** One leg of a transaction. */
    record Leg(
            UUID id,
            Account account,
            @Nullable UUID ownerId,
            long amountPaise,
            String direction,
            @Nullable UUID lotId) {}

    /** Where a transaction came from, as the console opens it; {@code page} is null for a cause with no page of its own. */
    record Source(
            String refType,
            UUID refId,
            @Nullable String page,
            @Nullable String pageId) {}

    record Transaction(UUID txnId, Source source, Instant createdAt, List<Leg> legs) {}

    Optional<Transaction> transaction(UUID txnId) {
        var rows = jdbc.sql("""
                        select id, account, owner_id, amount_paise, direction, lot_id, ref_type, ref_id, created_at
                        from billing.ledger_entries where txn_id = :txn order by direction desc, account, owner_id, id
                        """)
                .param("txn", txnId)
                .query((rs, n) -> new Object[] {
                    new Leg(
                            rs.getObject("id", UUID.class),
                            Account.fromDb(rs.getString("account")),
                            rs.getObject("owner_id", UUID.class),
                            rs.getLong("amount_paise"),
                            rs.getString("direction"),
                            rs.getObject("lot_id", UUID.class)),
                    rs.getString("ref_type"),
                    rs.getObject("ref_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant()
                })
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var first = rows.getFirst();
        var legs = rows.stream().map(row -> (Leg) row[0]).toList();
        return Optional.of(
                new Transaction(txnId, source(txnId, (String) first[1], (UUID) first[2]), (Instant) first[3], legs));
    }

    /** The console page that holds what each kind of transaction is about, by its {@code ref_type}. */
    private static final Map<String, String> PAGES = Map.ofEntries(
            Map.entry(TopUps.TOPUP_REF, "payment"),
            Map.entry(SessionChargeService.CHARGE_REF, "session"),
            Map.entry(SessionChargeService.REFUND_REF, "session"),
            Map.entry(CreditRefunds.APPROVE_REF, "refund"),
            Map.entry(HostPayoutService.PAYOUT_REF, "payout"),
            Map.entry(HostPayoutService.PAID_REF, "payout"),
            Map.entry(HostPayoutService.RETURNED_REF, "payout"),
            Map.entry(HostPayoutService.REVERSED_REF, "payout"),
            Map.entry(HostPayoutService.RECOVERED_REF, "payout"),
            Map.entry(Chargebacks.FREEZE_REF, "chargeback"),
            Map.entry(Chargebacks.SETTLE_REF, "chargeback"),
            Map.entry(BillingPersonalData.FORFEIT_REF, "user"),
            Map.entry(Adjustments.ADJUSTMENT_REF, "adjustment"),
            Map.entry(SessionRefundService.REFUND_REF, "session"),
            Map.entry(Adjustments.REVERSAL_REF, "adjustment"));

    /** A cause whose own id isn't a page's: the query that finds the page's id from it. */
    private static final Map<String, Map.Entry<String, String>> LOOKUPS = Map.of(
            CreditRefunds.PROCESSED_REF,
            Map.entry("refund", "select refund_id from billing.refund_parts where id = :id"),
            CreditRefunds.FAILED_REF,
            Map.entry("refund", "select refund_id from billing.refund_parts where id = :id"),
            CreditGrants.GRANT_REF,
            Map.entry("user", "select user_id from billing.credit_grants where id = :id"),
            CreditGrants.REVERSAL_REF,
            Map.entry(
                    "user",
                    "select g.user_id from billing.credit_grant_reversals r"
                            + " join billing.credit_grants g on g.id = r.grant_id where r.id = :id"),
            FreeCreditExpiry.EXPIRY_REF,
            Map.entry("user", "select user_id from billing.credit_lots where id = :id"));

    Source source(UUID txnId, String refType, UUID refId) {
        if (refType.equals(RazorpaySettlements.SETTLEMENT_REF)) {
            // A settlement's page is by Razorpay's id.
            var settlement = jdbc.sql("select id from billing.settlements where txn_id = :txn")
                    .param("txn", txnId)
                    .query(String.class)
                    .optional();
            return new Source(refType, refId, settlement.isPresent() ? "settlement" : null, settlement.orElse(null));
        }
        var page = PAGES.get(refType);
        if (page != null) {
            return new Source(refType, refId, page, refId.toString());
        }
        var lookup = LOOKUPS.get(refType);
        if (lookup != null) {
            var found = jdbc.sql(lookup.getValue())
                    .param("id", refId)
                    .query(UUID.class)
                    .optional();
            if (found.isPresent()) {
                return new Source(refType, refId, lookup.getKey(), found.get().toString());
            }
        }
        return new Source(refType, refId, null, null);
    }
}
