package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.identity.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in renter's own wallet and its history (ADR 0014), in integer paise. Read-only: it never
 * creates a wallet, and it reads only the caller's own entries.
 */
@RestController
@RequestMapping("/v1/wallet")
class WalletController {

    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 50;

    private final JdbcClient jdbc;

    WalletController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record WalletResponse(long balancePaise, long reservedPaise, long availablePaise) {}

    record Reference(String type, UUID id) {}

    record Entry(UUID id, long amountPaise, String direction, String kind, Reference reference, Instant createdAt) {}

    record EntryPage(List<Entry> entries, @Nullable String nextCursor) {}

    @GetMapping
    WalletResponse wallet(@AuthenticationPrincipal CurrentUser user) {
        return jdbc.sql("select balance_paise, reserved_paise from billing.wallets where user_id = :userId")
                .param("userId", user.id())
                .query((rs, n) -> {
                    long balance = rs.getLong("balance_paise");
                    long reserved = rs.getLong("reserved_paise");
                    return new WalletResponse(balance, reserved, balance - reserved);
                })
                .optional()
                .orElse(new WalletResponse(0, 0, 0));
    }

    /** Newest first. {@code cursor} is the previous page's {@code nextCursor}, opaque to clients. */
    @GetMapping("/transactions")
    EntryPage transactions(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be from 1 to " + MAX_PAGE + ".");
        }
        var after = cursor == null ? null : Cursor.decode(cursor);
        var sql = """
                select id, amount_paise, direction, ref_type, ref_id, created_at
                from billing.ledger_entries
                where account = 'renter_wallet' and owner_id = :userId
                """
                + (after == null ? "" : "and (created_at, id) < (:afterTime, :afterId)\n")
                + "order by created_at desc, id desc limit :limit";
        var statement = jdbc.sql(sql).param("userId", user.id()).param("limit", limit + 1);
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
                        kind(rs.getString("ref_type")),
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

    /** What a renter reads: the kind of money movement, in plain words. */
    static String kind(String refType) {
        return switch (refType) {
            case CreditGrants.GRANT_REF -> "free_credit";
            case CreditGrants.REVERSAL_REF -> "free_credit_reversed";
            case SessionChargeService.CHARGE_REF -> "session_charge";
            case SessionChargeService.REFUND_REF -> "session_refund";
            default -> refType;
        };
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
