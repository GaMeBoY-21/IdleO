package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.identity.CurrentUser;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a host has earned (ADR 0017), for its owner only: the balance of its {@code host_payable} account, which
 * each settlement credits with the host's share and each refund of lost minutes debits, and those entries, newest
 * first. Nothing is paid out or held in escrow yet; payouts come with the payment partner.
 */
@RestController
class HostEarningsController {

    private final HostOwners owners;
    private final LedgerPages pages;
    private final JdbcClient jdbc;

    HostEarningsController(HostOwners owners, LedgerPages pages, JdbcClient jdbc) {
        this.owners = owners;
        this.pages = pages;
        this.jdbc = jdbc;
    }

    record Earnings(UUID hostId, long balancePaise, LedgerPages.HistoryPage history) {}

    /** Another user's host, or one that doesn't exist, is a 404, so ids can't be probed. */
    @GetMapping("/v1/hosts/{hostId}/earnings")
    Earnings get(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID hostId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + LedgerPages.DEFAULT_PAGE) int limit) {
        if (!owners.owns(user.id(), hostId)) {
            throw ApiProblem.of(Problem.NOT_FOUND, "No such host.");
        }
        long balance = jdbc.sql("""
                        select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)
                        from billing.ledger_entries
                        where account = :account and owner_id = :hostId
                        """)
                .param("account", Account.HOST_PAYABLE.dbValue)
                .param("hostId", hostId)
                .query(Long.class)
                .single();
        return new Earnings(
                hostId, balance, pages.page(Account.HOST_PAYABLE, hostId, cursor, limit, HostEarningsController::kind));
    }

    /** What a host reads: the kind of money movement, in plain words. */
    static String kind(String refType) {
        return switch (refType) {
            case SessionChargeService.CHARGE_REF -> "session_earning";
            case SessionChargeService.REFUND_REF -> "lost_minutes_refunded";
            default -> refType;
        };
    }
}
