package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money, for the admin console (ADR 0014, ADR 0027): free credit granted and reversed through the same service as
 * {@code grant-credits} and {@code reverse-grant}, the ledger looked up by account or transaction, and the latest
 * reconciliation runs. Nothing here writes the ledger except through {@link CreditGrants}.
 */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnWebApplication
class AdminMoneyController {

    private final CreditGrants grants;
    private final JdbcClient jdbc;
    private final AuditTrail audit;

    AdminMoneyController(CreditGrants grants, JdbcClient jdbc, AuditTrail audit) {
        this.grants = grants;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Free credit for a user, in paise, with why; at most ₹5,000 a grant and ₹20,000 in a wallet. */
    record AdminCreditGrantRequest(
            @NotNull UUID userId,
            @Positive long paise,
            @NotBlank @Size(min = 5, max = 500) String reason) {}

    /** A grant, whether it has been reversed, and the wallet's balance after this request. */
    record AdminCreditGrant(
            UUID id,
            UUID userId,
            long amountPaise,
            String reason,
            String grantedBy,
            UUID txnId,
            Instant createdAt,
            @Nullable UUID reversalId,
            @Nullable String reversedBy,
            @Nullable Instant reversedAt,
            @Nullable Long walletBalancePaise) {}

    record AdminCreditGrantList(List<AdminCreditGrant> grants) {}

    /** One ledger entry: its transaction, account and owner, amount, direction, and what caused it. */
    record AdminLedgerEntry(
            UUID id,
            UUID txnId,
            String account,
            @Nullable UUID ownerId,
            long amountPaise,
            String direction,
            String refType,
            UUID refId,
            Instant createdAt) {}

    record AdminLedgerPage(
            List<AdminLedgerEntry> entries, @Nullable String nextCursor) {}

    /** One thing a reconciliation found wrong: {@code balance}, {@code reserved} or {@code hold}. */
    record AdminReconciliationMismatch(String kind, UUID userId, long walletPaise, long expectedPaise) {}

    /** A complete run: who asked, when it finished, the ledger's totals, and every mismatch. */
    record AdminReconciliationRun(
            UUID id,
            String trigger,
            Instant finishedAt,
            long ledgerDebitsPaise,
            long ledgerCreditsPaise,
            boolean ledgerBalanced,
            int mismatchCount,
            List<AdminReconciliationMismatch> mismatches) {}

    record AdminReconciliationRuns(List<AdminReconciliationRun> runs) {}

    /**
     * Grants free credit. The Idempotency-Key is the grant's own: the same key again for the same user and amount
     * returns the first grant (200), and for anything else it's refused.
     */
    @PostMapping("/credit-grants")
    ResponseEntity<AdminCreditGrant> grant(
            @AuthenticationPrincipal CurrentUser admin,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody AdminCreditGrantRequest request) {
        var key = IdempotentRequests.parseKey(idempotencyKey);
        var outcome = refusing(() ->
                grants.grant(request.userId(), Paise.of(request.paise()), request.reason(), key, audit.admin(admin)));
        var grant = find(outcome.grant().id(), outcome.walletBalance().value());
        return outcome.created()
                ? ResponseEntity.created(URI.create("/admin/v1/credit-grants?user=" + grant.userId()))
                        .body(grant)
                : ResponseEntity.ok(grant);
    }

    /** Reverses a grant with an opposite transaction; refused when the wallet's available credit can't cover it. */
    @PostMapping("/credit-grants/{id}/reverse")
    AdminCreditGrant reverse(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        var outcome = refusing(() -> grants.reverse(id, request.reason(), audit.admin(admin)));
        return find(id, outcome.walletBalance().value());
    }

    /** A user's grants, newest first. */
    @GetMapping("/credit-grants")
    AdminCreditGrantList list(@RequestParam UUID user) {
        return new AdminCreditGrantList(jdbc.sql(GRANTS + " where g.user_id = :user order by g.created_at desc")
                .param("user", user)
                .query((rs, n) -> grant(rs, null))
                .list());
    }

    /**
     * Ledger entries, newest first: one transaction's ({@code txn}), or one account's, such as a renter's wallet
     * ({@code account=renter_wallet&owner=<user>}) or the platform's revenue ({@code account=platform_revenue}).
     */
    @GetMapping("/ledger")
    AdminLedgerPage ledger(
            @RequestParam(required = false) @Nullable UUID txn,
            @RequestParam(required = false) @Nullable String account,
            @RequestParam(required = false) @Nullable UUID owner,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        if (txn == null && account == null) {
            throw ApiProblem.invalid("account", "Give a transaction, or an account and, for a wallet, its owner.");
        }
        String accountValue = null;
        if (account != null) {
            try {
                accountValue = Account.fromDb(account.strip()).dbValue;
            } catch (IllegalArgumentException e) {
                throw ApiProblem.invalid("account", "isn't one of the ledger's accounts.");
            }
        }
        int size = AdminPages.limit(limit);
        var entries = jdbc.sql("""
                        select * from billing.ledger_entries
                        where (cast(:txn as uuid) is null or txn_id = cast(:txn as uuid))
                          and (cast(:account as text) is null or account = cast(:account as text))
                          and (cast(:owner as uuid) is null or owner_id = cast(:owner as uuid))
                          and (cast(:after as uuid) is null or id < cast(:after as uuid))
                        order by id desc
                        limit :limit
                        """)
                .param("txn", txn)
                .param("account", accountValue)
                .param("owner", owner)
                .param("after", AdminPages.after(cursor))
                .param("limit", size + 1)
                .query((rs, n) -> new AdminLedgerEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("txn_id", UUID.class),
                        rs.getString("account"),
                        rs.getObject("owner_id", UUID.class),
                        rs.getLong("amount_paise"),
                        rs.getString("direction"),
                        rs.getString("ref_type"),
                        rs.getObject("ref_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        var page = entries.size() > size ? entries.subList(0, size) : entries;
        return new AdminLedgerPage(
                List.copyOf(page),
                entries.size() > size ? AdminPages.cursorAfter(page.getLast().id()) : null);
    }

    /** The latest complete reconciliation runs, newest first, each with what it found; {@code limit} 1 to 200. */
    @GetMapping("/reconciliation")
    AdminReconciliationRuns reconciliation(@RequestParam(required = false) @Nullable Integer limit) {
        record Run(UUID id, String trigger, Instant finishedAt, long debits, long credits, int mismatches) {}
        var runs = jdbc.sql("select * from billing.reconciliation_runs order by finished_at desc, id desc limit :limit")
                .param("limit", AdminPages.limit(limit == null ? 10 : limit))
                .query((rs, n) -> new Run(
                        rs.getObject("id", UUID.class),
                        rs.getString("trigger"),
                        rs.getTimestamp("finished_at").toInstant(),
                        rs.getLong("ledger_debits_paise"),
                        rs.getLong("ledger_credits_paise"),
                        rs.getInt("mismatches")))
                .list();
        var result = new ArrayList<AdminReconciliationRun>();
        for (var run : runs) {
            var mismatches = jdbc.sql("""
                            select kind, user_id, wallet_paise, expected_paise from billing.reconciliation_mismatches
                            where run_id = :runId order by position
                            """)
                    .param("runId", run.id())
                    .query((rs, n) -> new AdminReconciliationMismatch(
                            rs.getString("kind"),
                            rs.getObject("user_id", UUID.class),
                            rs.getLong("wallet_paise"),
                            rs.getLong("expected_paise")))
                    .list();
            result.add(new AdminReconciliationRun(
                    run.id(),
                    run.trigger(),
                    run.finishedAt(),
                    run.debits(),
                    run.credits(),
                    run.debits() == run.credits(),
                    run.mismatches(),
                    mismatches));
        }
        return new AdminReconciliationRuns(List.copyOf(result));
    }

    private static final String GRANTS = """
            select g.*, r.id as reversal_id, r.reversed_by, r.created_at as reversed_at
            from billing.credit_grants g left join billing.credit_grant_reversals r on r.grant_id = g.id
            """;

    private AdminCreditGrant find(UUID grantId, long walletBalancePaise) {
        return jdbc.sql(GRANTS + " where g.id = :id")
                .param("id", grantId)
                .query((rs, n) -> grant(rs, walletBalancePaise))
                .single();
    }

    private static AdminCreditGrant grant(java.sql.ResultSet rs, @Nullable Long walletBalancePaise)
            throws java.sql.SQLException {
        var reversedAt = rs.getTimestamp("reversed_at");
        return new AdminCreditGrant(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getLong("amount_paise"),
                rs.getString("reason"),
                rs.getString("granted_by"),
                rs.getObject("txn_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("reversal_id", UUID.class),
                rs.getString("reversed_by"),
                reversedAt == null ? null : reversedAt.toInstant(),
                walletBalancePaise);
    }

    private static <T> T refusing(Supplier<T> action) {
        try {
            return action.get();
        } catch (CreditGrants.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        }
    }
}
