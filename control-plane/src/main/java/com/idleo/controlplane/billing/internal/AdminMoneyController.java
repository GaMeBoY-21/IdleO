package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final WalletReconciliation reconciliation;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final Approvals approvals;

    AdminMoneyController(
            CreditGrants grants,
            WalletReconciliation reconciliation,
            JdbcClient jdbc,
            AuditTrail audit,
            Approvals approvals) {
        this.approvals = approvals;
        this.grants = grants;
        this.reconciliation = reconciliation;
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
            @Nullable Long walletBalancePaise,
            long version) {}

    record AdminCreditGrantList(
            List<AdminCreditGrant> grants, @Nullable String nextCursor) {}

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

    record AdminReconciliationRuns(
            List<AdminReconciliationRun> runs, @Nullable String nextCursor) {}

    /**
     * Asks to grant free credit: every manual money adjustment goes to another admin (ADR 0051).
     */
    @RequiresPermission(AdminPermission.MONEY_ADJUST)
    @PostMapping("/credit-grants")
    ResponseEntity<Approvals.AdminApproval> grant(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminCreditGrantRequest request) {
        return approvals.submit(
                admin,
                BillingGatedActions.CREDIT_GRANT,
                null,
                null,
                request,
                List.of(new Approvals.ApprovalChange(
                        "free credit for user " + request.userId(),
                        0,
                        Paise.of(request.paise()).value())),
                request.reason());
    }

    /** Asks to reverse a grant with an opposite transaction; refused when the wallet's available credit can't cover it. */
    @RequiresPermission(value = AdminPermission.MONEY_ADJUST, record = "credit_grant")
    @PostMapping("/credit-grants/{id}/reverse")
    ResponseEntity<Approvals.AdminApproval> reverse(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        var grant = jdbc.sql(GRANTS + " where g.id = :id")
                .param("id", id)
                .query((rs, n) -> grant(rs, null))
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no grant " + id + "."));
        return approvals.submit(
                admin,
                BillingGatedActions.CREDIT_REVERSE,
                "credit_grant",
                id.toString(),
                Map.of("id", id),
                List.of(new Approvals.ApprovalChange("free credit for user " + grant.userId(), grant.amountPaise(), 0)),
                request.reason());
    }

    /** A user's grants, newest first. */
    @RequiresPermission(AdminPermission.MONEY_READ)
    @AdminList("credit-grants")
    @GetMapping("/credit-grants")
    AdminCreditGrantList list(
            @RequestParam(required = false) @Nullable UUID user,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = GRANT_LIST.page(
                jdbc, AdminListing.Asked.of(sort, "user", user), cursor, limit, (rs, n) -> grant(rs, null));
        return new AdminCreditGrantList(page.rows(), page.nextCursor());
    }

    /**
     * Ledger entries, newest first: one transaction's ({@code txn}), or one account's, such as a renter's wallet
     * ({@code account=renter_wallet&owner=<user>}) or the platform's revenue ({@code account=platform_revenue}).
     */
    @RequiresPermission(AdminPermission.MONEY_READ)
    @AdminList("ledger")
    @GetMapping("/ledger")
    AdminLedgerPage ledger(
            @RequestParam(required = false) @Nullable UUID txn,
            @RequestParam(required = false) @Nullable String account,
            @RequestParam(required = false) @Nullable UUID owner,
            @RequestParam(required = false) @Nullable String sort,
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
        var page = LEDGER.page(
                jdbc,
                AdminListing.Asked.of(sort, "txn", txn, "account", accountValue, "owner", owner),
                cursor,
                limit,
                (rs, n) -> new AdminLedgerEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("txn_id", UUID.class),
                        rs.getString("account"),
                        rs.getObject("owner_id", UUID.class),
                        rs.getLong("amount_paise"),
                        rs.getString("direction"),
                        rs.getString("ref_type"),
                        rs.getObject("ref_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()));
        return new AdminLedgerPage(page.rows(), page.nextCursor());
    }

    /**
     * Runs the wallet check now, as {@code reconcile-wallets} does, and returns the run. 409 {@code action-refused}
     * while another instance is running it.
     */
    @RequiresPermission(AdminPermission.RECONCILIATION_RUN)
    @PostMapping("/reconciliation")
    AdminReconciliationRun reconcile(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminReason request) {
        var result = reconciliation.run(audit.admin(admin), request.reason());
        if (!result.ran() || result.runId() == null) {
            throw ApiProblem.of(
                    Problem.ACTION_REFUSED, "Another instance is running the check; try again in a minute.");
        }
        return found(Objects.requireNonNull(jdbc.sql("select * from billing.reconciliation_runs where id = :id")
                .param("id", result.runId())
                .query(this::run)
                .single()));
    }

    /** The latest complete reconciliation runs, newest first, each with what it found; {@code limit} 1 to 200. */
    @RequiresPermission(AdminPermission.MONEY_READ)
    @AdminList("wallet-reconciliation")
    @GetMapping("/reconciliation")
    AdminReconciliationRuns reconciliation(
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = WALLET_RUNS.page(jdbc, AdminListing.Asked.of(sort), cursor, limit == null ? 10 : limit, this::run);
        return new AdminReconciliationRuns(page.rows().stream().map(this::found).toList(), page.nextCursor());
    }

    private record Run(UUID id, String trigger, Instant finishedAt, long debits, long credits, int mismatches) {}

    private Run run(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getString("trigger"),
                rs.getTimestamp("finished_at").toInstant(),
                rs.getLong("ledger_debits_paise"),
                rs.getLong("ledger_credits_paise"),
                rs.getInt("mismatches"));
    }

    /** The run with what it found. */
    private AdminReconciliationRun found(Run run) {
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
        return new AdminReconciliationRun(
                run.id(),
                run.trigger(),
                run.finishedAt(),
                run.debits(),
                run.credits(),
                run.debits() == run.credits(),
                run.mismatches(),
                mismatches);
    }

    private static final String GRANTS = """
            select g.*, r.id as reversal_id, r.reversed_by, r.created_at as reversed_at
            from billing.credit_grants g left join billing.credit_grant_reversals r on r.grant_id = g.id
            """;

    /** Free credit granted, its orders and CSV (ADR 0052). */
    static final AdminListing GRANT_LIST = AdminListing.of("credit-grants", AdminPermission.MONEY_READ, true)
            .select("g.*, r.id as reversal_id, r.reversed_by, r.created_at as reversed_at")
            .from("billing.credit_grants g left join billing.credit_grant_reversals r on r.grant_id = g.id")
            .id("g.id")
            .sort("createdAt", AdminListing.Type.UUID, "g.id")
            .sort("amount", AdminListing.Type.NUMBER, "g.amount_paise")
            .sortedBy("createdAt", true)
            .filter("user", "g.user_id = cast(:user as uuid)")
            .csv("id", "g.id")
            .csv("userId", "g.user_id")
            .csv("amountPaise", "g.amount_paise")
            .csv("reason", "g.reason")
            .csv("grantedBy", "g.granted_by")
            .csv("createdAt", "g.created_at")
            .csv("reversedAt", "r.created_at")
            .build();

    /** The ledger's entries, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LEDGER = AdminListing.of("ledger", AdminPermission.MONEY_READ, true)
            .from("billing.ledger_entries")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sort("amount", AdminListing.Type.NUMBER, "amount_paise")
            .sortedBy("createdAt", true)
            .filter("txn", "txn_id = cast(:txn as uuid)")
            .filter("account", "account = cast(:account as text)")
            .filter("owner", "owner_id = cast(:owner as uuid)")
            .csv("id", "id")
            .csv("txnId", "txn_id")
            .csv("account", "account")
            .csv("ownerId", "owner_id")
            .csv("amountPaise", "amount_paise")
            .csv("direction", "direction")
            .csv("refType", "ref_type")
            .csv("refId", "ref_id")
            .csv("createdAt", "created_at")
            .build();

    /** The wallet check's runs, their orders and CSV (ADR 0052). */
    static final AdminListing WALLET_RUNS = AdminListing.of("wallet-reconciliation", AdminPermission.MONEY_READ, false)
            .from("billing.reconciliation_runs")
            .sort("finishedAt", AdminListing.Type.TIME, "finished_at")
            .sort("mismatches", AdminListing.Type.NUMBER, "mismatches")
            .sortedBy("finishedAt", true)
            .csv("id", "id")
            .csv("trigger", "trigger")
            .csv("finishedAt", "finished_at")
            .csv("ledgerDebitsPaise", "ledger_debits_paise")
            .csv("ledgerCreditsPaise", "ledger_credits_paise")
            .csv("mismatches", "mismatches")
            .build();

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
                walletBalancePaise,
                rs.getObject("reversal_id") == null ? 1 : 2);
    }

    private static <T> T refusing(Supplier<T> action) {
        try {
            return action.get();
        } catch (CreditGrants.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        }
    }
}
