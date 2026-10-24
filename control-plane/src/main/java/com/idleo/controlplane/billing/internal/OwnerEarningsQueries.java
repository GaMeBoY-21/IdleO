package com.idleo.controlplane.billing.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * An owner's earnings, summed from their hosts' {@code host_payable} entries and nothing else, so every figure matches
 * the ledger (ADR 0023). A credit adds, a debit (lost minutes given back) subtracts. Only what a session caused counts
 * as earning ({@link HostPayoutService#EARNING_REFS}); what payouts, recoveries and clawbacks move between the accounts
 * isn't earning (ADR 0040). Read-only.
 */
@Component
class OwnerEarningsQueries {

    private static final String NET =
            "coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)";

    /** One entry of the owner's history. */
    record Entry(UUID id, UUID txnId, UUID hostId, long amountPaise, String refType, UUID refId, Instant createdAt) {}

    private final JdbcClient jdbc;

    OwnerEarningsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What each host has earned in all, less what was given back; hosts without entries aren't in the map. */
    Map<UUID, Long> totals(Collection<UUID> hostIds) {
        var totals = new HashMap<UUID, Long>();
        jdbc.sql(
                        "select owner_id, " + NET + " as net from billing.ledger_entries"
                                + " where account = :account and owner_id in (:hostIds) and ref_type in (:refs) group by owner_id")
                .param("account", Account.HOST_PAYABLE.dbValue)
                .param("refs", HostPayoutService.EARNING_REFS)
                .param("hostIds", hostIds)
                .query(rs -> {
                    totals.put(rs.getObject("owner_id", UUID.class), rs.getLong("net"));
                });
        return totals;
    }

    /** The net earned from {@code from} up to, not including, {@code to}. */
    long between(Collection<UUID> hostIds, Instant from, Instant to) {
        return jdbc.sql("select " + NET + " from billing.ledger_entries where account = :account"
                        + " and owner_id in (:hostIds) and ref_type in (:refs) and created_at >= :from"
                        + " and created_at < :to")
                .param("account", Account.HOST_PAYABLE.dbValue)
                .param("refs", HostPayoutService.EARNING_REFS)
                .param("hostIds", hostIds)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(Long.class)
                .single();
    }

    /** The net earned on each calendar day of {@code zone} from {@code from}, in days that had any entries. */
    Map<LocalDate, Long> perDay(Collection<UUID> hostIds, Instant from, String zone) {
        var days = new TreeMap<LocalDate, Long>();
        jdbc.sql("select (created_at at time zone :zone)::date as day, " + NET + " as net from billing.ledger_entries"
                        + " where account = :account and owner_id in (:hostIds) and ref_type in (:refs)"
                        + " and created_at >= :from group by day")
                .param("zone", zone)
                .param("account", Account.HOST_PAYABLE.dbValue)
                .param("refs", HostPayoutService.EARNING_REFS)
                .param("hostIds", hostIds)
                .param("from", Timestamp.from(from))
                .query(rs -> {
                    days.put(rs.getObject("day", LocalDate.class), rs.getLong("net"));
                });
        return days;
    }

    /** Every settlement or refund entry created from {@code from} up to {@code to}, to count their minutes. */
    List<Entry> entriesBetween(Collection<UUID> hostIds, Instant from, Instant to) {
        return select(
                hostIds, "and created_at >= :from and created_at < :to", "order by created_at, id", null, from, to);
    }

    /** A page of the owner's entries, newest first, after {@code after}. */
    List<Entry> page(Collection<UUID> hostIds, LedgerPages.@Nullable Cursor after, int limit) {
        return select(
                hostIds,
                after == null ? "" : "and (created_at, id) < (:afterTime, :afterId)",
                "order by created_at desc, id desc limit " + limit,
                after,
                null,
                null);
    }

    private List<Entry> select(
            Collection<UUID> hostIds,
            String filter,
            String order,
            LedgerPages.@Nullable Cursor after,
            @Nullable Instant from,
            @Nullable Instant to) {
        var statement = jdbc.sql("""
                        select id, txn_id, owner_id, amount_paise, direction, ref_type, ref_id, created_at
                        from billing.ledger_entries
                        where account = :account and owner_id in (:hostIds) and ref_type in (:refs)
                        """ + filter + "\n" + order)
                .param("account", Account.HOST_PAYABLE.dbValue)
                .param("refs", HostPayoutService.EARNING_REFS)
                .param("hostIds", hostIds);
        if (after != null) {
            statement = statement
                    .param("afterTime", Timestamp.from(after.createdAt()))
                    .param("afterId", after.id());
        }
        if (from != null && to != null) {
            statement = statement.param("from", Timestamp.from(from)).param("to", Timestamp.from(to));
        }
        return statement
                .query((rs, n) -> new Entry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("txn_id", UUID.class),
                        rs.getObject("owner_id", UUID.class),
                        rs.getString("direction").equals("credit")
                                ? rs.getLong("amount_paise")
                                : -rs.getLong("amount_paise"),
                        rs.getString("ref_type"),
                        rs.getObject("ref_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
