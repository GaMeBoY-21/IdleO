package com.idleo.controlplane.billing.internal;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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

    record Reference(String type, UUID id) {}

    record Entry(UUID id, long amountPaise, String direction, String kind, Reference reference, Instant createdAt) {}

    record EntryPage(List<Entry> entries, @Nullable String nextCursor) {}

    /**
     * A page of the owner's entries in {@code account}. {@code cursor} is the previous page's {@code nextCursor},
     * opaque to clients; {@code kind} names each entry's reference type in plain words.
     */
    EntryPage page(Account account, UUID ownerId, @Nullable String cursor, int limit, UnaryOperator<String> kind) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be from 1 to " + MAX_PAGE + ".");
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
                .query((rs, n) -> new Entry(
                        rs.getObject("id", UUID.class),
                        rs.getLong("amount_paise"),
                        rs.getString("direction"),
                        kind.apply(rs.getString("ref_type")),
                        new Reference(rs.getString("ref_type"), rs.getObject("ref_id", UUID.class)),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        if (rows.size() <= limit) {
            return new EntryPage(rows, null);
        }
        var page = rows.subList(0, limit);
        var last = page.getLast();
        return new EntryPage(List.copyOf(page), new Cursor(last.createdAt(), last.id()).encode());
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
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That cursor isn't valid.");
            }
        }
    }
}
