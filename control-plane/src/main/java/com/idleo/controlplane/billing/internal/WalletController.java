package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.identity.CurrentUser;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in renter's own wallet and its history (ADR 0014), in integer paise. Read-only: it never
 * creates a wallet, and it reads only the caller's own entries.
 */
@RestController
@RequestMapping("/v1/wallet")
class WalletController {

    private final JdbcClient jdbc;
    private final LedgerPages pages;

    WalletController(JdbcClient jdbc, LedgerPages pages) {
        this.jdbc = jdbc;
        this.pages = pages;
    }

    /**
     * The renter's credit (ADR 0039). {@code balancePaise} is {@code boughtPaise} plus {@code freePaise}; {@code
     * freeExpiresAt} is when the soonest of the free credit expires (null when none does); {@code refundingPaise} is
     * bought credit on its way back to where it was paid from, and {@code frozenPaise} credit held while a chargeback
     * is open: neither is in the balance.
     */
    record WalletResponse(
            long balancePaise,
            long reservedPaise,
            long availablePaise,
            long boughtPaise,
            long freePaise,
            @Nullable Instant freeExpiresAt,
            long refundingPaise,
            long frozenPaise) {}

    @GetMapping
    WalletResponse get(@AuthenticationPrincipal CurrentUser user) {
        return jdbc.sql("""
                        select coalesce(w.balance_paise, 0) as balance, coalesce(w.reserved_paise, 0) as reserved,
                               coalesce((select sum(remaining_paise) from billing.credit_lots
                                         where user_id = :userId and kind = 'purchased'), 0) as bought,
                               coalesce((select sum(remaining_paise) from billing.credit_lots
                                         where user_id = :userId and kind = 'free'), 0) as free,
                               (select min(expires_at) from billing.credit_lots
                                where user_id = :userId and remaining_paise > 0 and expires_at is not null) as expires,
                               coalesce((select sum(case direction when 'credit' then amount_paise else -amount_paise end)
                                         from billing.ledger_entries
                                         where owner_id = :userId and account = 'refunds_pending'), 0) as refunding,
                               coalesce((select sum(case direction when 'credit' then amount_paise else -amount_paise end)
                                         from billing.ledger_entries
                                         where owner_id = :userId and account = 'disputes_frozen'), 0) as frozen
                        from (select 1) one
                        left join billing.wallets w on w.user_id = :userId
                        """)
                .param("userId", user.id())
                .query((rs, n) -> {
                    long balance = rs.getLong("balance");
                    long reserved = rs.getLong("reserved");
                    var expires = rs.getTimestamp("expires");
                    return new WalletResponse(
                            balance,
                            reserved,
                            balance - reserved,
                            rs.getLong("bought"),
                            rs.getLong("free"),
                            expires == null ? null : expires.toInstant(),
                            rs.getLong("refunding"),
                            rs.getLong("frozen"));
                })
                .single();
    }

    /** Newest first. {@code cursor} is the previous page's {@code nextCursor}, opaque to clients. */
    @GetMapping("/transactions")
    LedgerPages.HistoryPage transactions(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + LedgerPages.DEFAULT_PAGE) int limit) {
        return pages.renterPage(user.id(), cursor, limit, WalletController::kind);
    }

    /** What a renter reads: the kind of money movement, in plain words. */
    static String kind(String refType) {
        return switch (refType) {
            case CreditGrants.GRANT_REF -> "free_credit";
            case CreditGrants.REVERSAL_REF -> "free_credit_reversed";
            case SessionChargeService.CHARGE_REF -> "session_charge";
            case SessionChargeService.REFUND_REF -> "session_refund";
            case SessionRefundService.REFUND_REF -> "dispute_refund";
            case FreeCreditExpiry.EXPIRY_REF -> "free_credit_expired";
            case BillingPersonalData.FORFEIT_REF -> "free_credit_forfeited";
            default -> refType;
        };
    }
}
