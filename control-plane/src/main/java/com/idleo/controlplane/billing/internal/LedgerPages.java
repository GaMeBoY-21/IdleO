package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One owner's ledger entries, newest first, a page at a time (ADR 0014): a renter's wallet or a host's payable.
 * Read-only, and only ever the one owner's entries.
 */
@Component
class LedgerPages {

    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 50;

    private final JdbcClient jdbc;

    LedgerPages(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record HistoryReference(String type, UUID id) {}

    /**
     * One movement of money. {@code boughtPaise}, for a renter, is the part of it that was bought credit (ADR 0039):
     * the rest was free credit; null for a host's earnings.
     */
    record HistoryEntry(
            UUID id,
            long amountPaise,
            String direction,
            String kind,
            HistoryReference reference,
            Instant createdAt,
            @Nullable Long boughtPaise) {}

    record HistoryPage(List<HistoryEntry> entries, @Nullable String nextCursor) {}

    /**
     * A page of the owner's entries in {@code account}. {@code cursor} is the previous page's {@code nextCursor},
     * opaque to clients; {@code kind} names each entry's reference type in plain words.
     */
    HistoryPage page(Account account, UUID ownerId, @Nullable String cursor, int limit, UnaryOperator<String> kind) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiProblem.invalid("limit", "must be from 1 to " + MAX_PAGE + ".");
        }
        var after = cursor == null ? null : Cursor.decode(cursor);
        var sql = """
                select id, amount_paise, direction, ref_type, ref_id, created_at
                from billing.ledger_entries
                where account = :account and owner_id = :ownerId
                """
                + (after == null ? "" : "and (created_at, id) < (:afterTime, :afterId)\n")
                + "order by created_at desc, id desc limit :limit";
        var statement = jdbc.sql(sql)
                .param("account", account.dbValue)
                .param("ownerId", ownerId)
                .param("limit", limit + 1);
        if (after != null) {
            statement = statement
                    .param("afterTime", Timestamp.from(after.createdAt()))
                    .param("afterId", after.id());
        }
        var rows = statement
                .query((rs, n) -> new HistoryEntry(
                        rs.getObject("id", UUID.class),
                        rs.getLong("amount_paise"),
                        rs.getString("direction"),
                        kind.apply(rs.getString("ref_type")),
                        new HistoryReference(rs.getString("ref_type"), rs.getObject("ref_id", UUID.class)),
                        rs.getTimestamp("created_at").toInstant(),
                        null))
                .list();
        return paged(rows, limit);
    }

    /**
     * A page of a renter's credit history: one row a ledger transaction across their free and bought credit, however
     * many lots it touched (ADR 0039), with the part that was bought credit. A transaction that moved credit between
     * lots and left the total as it was (the move into lots, V49) isn't shown.
     */
    HistoryPage renterPage(UUID ownerId, @Nullable String cursor, int limit, UnaryOperator<String> kind) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiProblem.invalid("limit", "must be from 1 to " + MAX_PAGE + ".");
        }
        var after = cursor == null ? null : Cursor.decode(cursor);
        var sql = """
                select * from (
                    select txn_id as id, min(created_at) as created_at, min(ref_type) as ref_type,
                           (array_agg(ref_id))[1] as ref_id,
                           sum(case direction when 'credit' then amount_paise else -amount_paise end) as net,
                           sum(case direction when 'credit' then amount_paise else -amount_paise end)
                               filter (where account = 'renter_purchased') as bought
                    from billing.ledger_entries
                    where owner_id = :ownerId and account in ('renter_wallet', 'renter_purchased')
                    group by txn_id
                    having sum(case direction when 'credit' then amount_paise else -amount_paise end) <> 0
                ) history
                """
                + (after == null ? "" : "where (created_at, id) < (:afterTime, :afterId)\n")
                + "order by created_at desc, id desc limit :limit";
        var statement = jdbc.sql(sql).param("ownerId", ownerId).param("limit", limit + 1);
        if (after != null) {
            statement = statement
                    .param("afterTime", Timestamp.from(after.createdAt()))
                    .param("afterId", after.id());
        }
        var rows = statement
                .query((rs, n) -> {
                    long net = rs.getLong("net");
                    long bought = rs.getLong("bought");
                    return new HistoryEntry(
                            rs.getObject("id", UUID.class),
                            Math.abs(net),
                            net > 0 ? "credit" : "debit",
                            kind.apply(rs.getString("ref_type")),
                            new HistoryReference(rs.getString("ref_type"), rs.getObject("ref_id", UUID.class)),
                            rs.getTimestamp("created_at").toInstant(),
                            Math.abs(bought));
                })
                .list();
        return paged(rows, limit);
    }

    private static HistoryPage paged(List<HistoryEntry> rows, int limit) {
        if (rows.size() <= limit) {
            return new HistoryPage(rows, null);
        }
        var page = rows.subList(0, limit);
        var last = page.getLast();
        return new HistoryPage(List.copyOf(page), new Cursor(last.createdAt(), last.id()).encode());
    }

    /** A position in the history: the last entry's time and id, base64url-encoded. */
    record Cursor(Instant createdAt, UUID id) {

        String encode() {
            var raw = createdAt.getEpochSecond() + "." + createdAt.getNano() + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                var raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                var colon = raw.indexOf(':');
                var dot = raw.indexOf('.');
                var instant = Instant.ofEpochSecond(
                        Long.parseLong(raw.substring(0, dot)), Long.parseLong(raw.substring(dot + 1, colon)));
                return new Cursor(instant, UUID.fromString(raw.substring(colon + 1)));
            } catch (RuntimeException e) {
                throw ApiProblem.invalid("cursor", "isn't a cursor this API gave out.");
            }
        }
    }
}
