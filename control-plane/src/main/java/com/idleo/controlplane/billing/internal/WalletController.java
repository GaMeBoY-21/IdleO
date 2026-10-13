package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.identity.CurrentUser;
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

    record WalletResponse(long balancePaise, long reservedPaise, long availablePaise) {}

    @GetMapping
    WalletResponse get(@AuthenticationPrincipal CurrentUser user) {
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
    LedgerPages.HistoryPage transactions(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + LedgerPages.DEFAULT_PAGE) int limit) {
        return pages.page(Account.RENTER_WALLET, user.id(), cursor, limit, WalletController::kind);
    }

    /** What a renter reads: the kind of money movement, in plain words. */
    static String kind(String refType) {
        return switch (refType) {
            case CreditGrants.GRANT_REF -> "free_credit";
            case CreditGrants.REVERSAL_REF -> "free_credit_reversed";
            case SessionChargeService.CHARGE_REF -> "session_charge";
            case SessionChargeService.REFUND_REF -> "session_refund";
            case SessionRefundService.REFUND_REF -> "dispute_refund";
            default -> refType;
        };
    }
}
