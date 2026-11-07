package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payouts in the admin console (ADR 0040), every action with a reason and audited:
 *
 * <ul>
 *   <li>Payouts: the latest runs, the payouts by status, the last balance check; start a run now; pay one person
 *       again now (after a payout failed).
 *   <li>Verification: PANs and UPI IDs waiting for an admin; verify or reject; read a PAN whole.
 *   <li>Payout reconciliation: RazorpayX against our payouts, a day at a time; accept an item.
 *   <li>Tax: the quarter's tax deducted at source, as CSV for the CA, with each host's whole PAN.
 * </ul>
 */
@RestController
@RequestMapping("/admin/v1/payouts")
@ConditionalOnWebApplication
class AdminPayoutsController {

    /** The list, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("payouts", AdminPermission.PAYOUTS_READ, true)
            .from("payouts.payouts")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sort("net", AdminListing.Type.NUMBER, "net_paise")
            .sort("status", AdminListing.Type.TEXT, "status")
            .sortedBy("createdAt", true)
            .filter("status", "status = cast(:status as text)")
            .filter("run", "run_id = cast(:run as uuid)")
            .filter("user", "user_id = cast(:user as uuid)")
            .filter("tagged", "user_id in (:tagged)")
            .csv("id", "id")
            .csv("userId", "user_id")
            .csv("kind", "kind")
            .csv("grossPaise", "gross_paise")
            .csv("tdsPaise", "tds_paise")
            .csv("netPaise", "net_paise")
            .csv("status", "status")
            .csv("createdAt", "created_at")
            .csv("processedAt", "processed_at")
            .build();

    static final String RELEASE = "payout.release";

    private static final Set<String> STATUSES = Set.of(
            "all",
            "created",
            "queued",
            "pending",
            "processing",
            "processed",
            "failed",
            "rejected",
            "cancelled",
            "reversed");

    private final PayoutService payouts;
    private final PayoutRuns runs;
    private final BalanceCheck balance;
    private final Pans pans;
    private final PayoutMethods methods;
    private final PayoutReconciliation reconciliation;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Approvals approvals;
    private final AdminTagged tagged;

    AdminPayoutsController(
            PayoutService payouts,
            PayoutRuns runs,
            BalanceCheck balance,
            Pans pans,
            PayoutMethods methods,
            PayoutReconciliation reconciliation,
            AuditTrail audit,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Approvals approvals,
            AdminTagged tagged) {
        this.tagged = tagged;
        this.approvals = approvals;
        this.payouts = payouts;
        this.runs = runs;
        this.balance = balance;
        this.pans = pans;
        this.methods = methods;
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    record AdminPayoutRun(
            UUID id,
            String kind,
            LocalDate payday,
            String trigger,
            Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable Integer owners,
            @Nullable Integer payouts,
            @Nullable Long totalPaise,
            @Nullable String failure,

            @Schema(allowableValues = {"preview", "released"})
            String state,

            long version,
            @Nullable Instant releasedAt,
            @Nullable String releasedBy) {

        static AdminPayoutRun of(PayoutRuns.Run run) {
            return new AdminPayoutRun(
                    run.id(),
                    run.kind(),
                    run.payday(),
                    run.trigger(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.owners(),
                    run.payouts(),
                    run.totalPaise(),
                    run.failure(),
                    run.state(),
                    run.version(),
                    run.releasedAt(),
                    run.releasedBy());
        }
    }

    record AdminPayout(
            UUID id,
            UUID userId,
            @Nullable UUID runId,
            String kind,
            long grossPaise,
            long tdsPaise,
            String tdsSection,
            long feePaise,
            long netPaise,
            String status,
            @Nullable String razorpayxId,
            @Nullable String utr,
            @Nullable String statusReason,
            @Nullable String statusDescription,
            int attempts,
            @Nullable String lastError,
            Instant createdAt,
            @Nullable Instant processedAt,
            @Nullable Instant endedAt) {

        static AdminPayout of(PayoutService.Payout payout) {
            return new AdminPayout(
                    payout.id(),
                    payout.userId(),
                    payout.runId(),
                    payout.kind(),
                    payout.grossPaise(),
                    payout.tdsPaise(),
                    payout.tdsSection(),
                    payout.feePaise(),
                    payout.netPaise(),
                    payout.status(),
                    payout.razorpayxId(),
                    payout.utr(),
                    payout.statusReason(),
                    payout.statusDescription(),
                    payout.attempts(),
                    payout.lastError(),
                    payout.createdAt(),
                    payout.processedAt(),
                    payout.endedAt());
        }
    }

    /** The last balance check: short means the next run's payouts will queue at RazorpayX. */
    record AdminBalanceCheck(
            LocalDate payday,
            Instant checkedAt,
            @Nullable Long balancePaise,
            long duePaise,
            boolean isShort,
            @Nullable String failure) {}

    record AdminPayouts(
            List<AdminPayoutRun> runs,
            List<AdminPayout> payouts,
            @Nullable String nextCursor,
            @Nullable AdminBalanceCheck balance,
            Instant nextPayday) {}

    record RetryPayout(
            @NotNull UUID userId,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    @RequiresPermission(AdminPermission.PAYOUTS_READ)
    @AdminList("payouts")
    @GetMapping
    AdminPayouts list(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) @Nullable UUID run,
            @RequestParam(required = false) @Nullable UUID user,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        if (!STATUSES.contains(status)) {
            throw ApiProblem.invalid("status", "isn't a payout status.");
        }
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(
                        sort,
                        "status",
                        status.equals("all") ? null : status,
                        "run",
                        run,
                        "user",
                        user,
                        "tagged",
                        tagged.subjects("user", tag)),
                cursor,
                limit,
                PayoutService::payout);
        return new AdminPayouts(
                runs.latest(10).stream().map(AdminPayoutRun::of).toList(),
                page.rows().stream().map(AdminPayout::of).toList(),
                page.nextCursor(),
                balance.latest()
                        .map(check -> new AdminBalanceCheck(
                                check.payday(),
                                check.checkedAt(),
                                check.balancePaise(),
                                check.duePaise(),
                                check.isShort(),
                                check.failure()))
                        .orElse(null),
                runs.nextPayday());
    }

    /**
     * Prepares a batch now, outside the schedule: a preview of everyone who could be paid at least the weekly minimum.
     * Nothing is paid until it's released (ADR 0055).
     */
    @RequiresPermission(AdminPermission.PAYOUTS_RUN)
    @PostMapping("/runs")
    AdminPayoutRun run(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminReason request) {
        var run = runs.startNow(request.reason(), audit.admin(admin));
        return AdminPayoutRun.of(runs.prepare(run));
    }

    @RequiresPermission(AdminPermission.PAYOUTS_READ)
    @GetMapping("/{id}")
    AdminPayout get(@PathVariable UUID id) {
        return payouts.find(id)
                .map(AdminPayout::of)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such payout."));
    }

    /** Pays one person now (a retry after a failed payout), whatever the weekly minimum. */
    @RequiresPermission(AdminPermission.PAYOUTS_RELEASE)
    @PostMapping("/retry")
    ResponseEntity<Approvals.AdminApproval> retry(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody RetryPayout request) {
        return approvals.submit(
                admin,
                RELEASE,
                null,
                null,
                request,
                List.of(new Approvals.ApprovalChange(
                        "payout to user " + request.userId(), "held or failed", "paid now")),
                request.reason());
    }

    // Verification.

    record AdminPanReview(
            UUID userId,
            String last4,
            String name,
            String holder,
            String state,
            String checked,
            String verifier,
            Instant submittedAt,
            @Nullable String reason,
            long version) {

        static AdminPanReview of(Pans.Pan pan) {
            return new AdminPanReview(
                    pan.userId(),
                    pan.last4(),
                    pan.name(),
                    pan.holder(),
                    pan.state(),
                    pan.checked(),
                    pan.verifier(),
                    pan.submittedAt(),
                    pan.reason(),
                    pan.version());
        }
    }

    /**
     * A UPI ID waiting for an admin: the name the bank has for it beside the name on the person's PAN, and how well
     * they matched.
     */
    record AdminUpiReview(
            UUID id,
            UUID userId,
            @Nullable String vpa,
            @Nullable String registeredName,
            @Nullable String panName,
            @Nullable Integer nameMatchScore,
            String state,
            Instant checkedAt,
            @Nullable Instant confirmedAt,
            long version) {}

    record AdminPanReviews(
            List<AdminPanReview> pans, @Nullable String nextCursor) {}

    record AdminUpiReviews(
            List<AdminUpiReview> upiIds, @Nullable String nextCursor) {}

    @RequiresPermission(AdminPermission.VERIFICATIONS_READ)
    @AdminList("verification-pans")
    @GetMapping("/verifications/pans")
    AdminPanReviews panReviews(
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = pans.inReview(sort, cursor, limit);
        return new AdminPanReviews(page.rows().stream().map(AdminPanReview::of).toList(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.VERIFICATIONS_READ)
    @AdminList("verification-upi-ids")
    @GetMapping("/verifications/upi-ids")
    AdminUpiReviews upiReviews(
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = methods.reviews(sort, cursor, limit);
        return new AdminUpiReviews(
                page.rows().stream()
                        .map(method -> new AdminUpiReview(
                                method.id(),
                                method.userId(),
                                method.vpa(),
                                method.registeredName(),
                                pans.find(method.userId()).map(Pans.Pan::name).orElse(null),
                                method.nameMatchScore(),
                                method.state(),
                                method.checkedAt(),
                                method.confirmedAt(),
                                method.version()))
                        .toList(),
                page.nextCursor());
    }

    @RequiresPermission(value = AdminPermission.VERIFICATIONS_DECIDE, record = "pan")
    @PostMapping("/verifications/pans/{userId}/verify")
    AdminPanReview verifyPan(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminReason request) {
        return AdminPanReview.of(refusing(() -> pans.decide(userId, true, request.reason(), audit.admin(admin))));
    }

    @RequiresPermission(value = AdminPermission.VERIFICATIONS_DECIDE, record = "pan")
    @PostMapping("/verifications/pans/{userId}/reject")
    AdminPanReview rejectPan(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminReason request) {
        return AdminPanReview.of(refusing(() -> pans.decide(userId, false, request.reason(), audit.admin(admin))));
    }

    @RequiresPermission(value = AdminPermission.VERIFICATIONS_DECIDE, record = "payout_method")
    @PostMapping("/verifications/upi-ids/{id}/approve")
    AdminUpiReview approveUpi(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return upi(refusing(() -> methods.decide(id, true, request.reason(), audit.admin(admin))));
    }

    @RequiresPermission(value = AdminPermission.VERIFICATIONS_DECIDE, record = "payout_method")
    @PostMapping("/verifications/upi-ids/{id}/reject")
    AdminUpiReview rejectUpi(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return upi(refusing(() -> methods.decide(id, false, request.reason(), audit.admin(admin))));
    }

    private AdminUpiReview upi(PayoutMethods.Method method) {
        return new AdminUpiReview(
                method.id(),
                method.userId(),
                method.vpa(),
                method.registeredName(),
                pans.find(method.userId()).map(Pans.Pan::name).orElse(null),
                method.nameMatchScore(),
                method.state(),
                method.checkedAt(),
                method.confirmedAt(),
                method.version());
    }

    // Reconciliation.

    record AdminPayoutMismatch(
            UUID id,
            String kind,
            String subject,
            @Nullable UUID payoutId,
            @Nullable UUID userId,
            @Nullable Long ourPaise,
            @Nullable Long theirPaise,
            @Nullable String ourStatus,
            @Nullable String theirStatus,
            String detail,
            String state,
            Instant foundAt,
            Instant lastFoundAt,
            @Nullable Instant closedAt,
            @Nullable String closedBy,
            @Nullable String closeReason,
            long version) {

        static AdminPayoutMismatch of(PayoutReconciliation.Mismatch item) {
            return new AdminPayoutMismatch(
                    item.id(),
                    item.kind(),
                    item.subject(),
                    item.payoutId(),
                    item.userId(),
                    item.ourPaise(),
                    item.theirPaise(),
                    item.ourStatus(),
                    item.theirStatus(),
                    item.detail(),
                    item.state(),
                    item.foundAt(),
                    item.lastFoundAt(),
                    item.closedAt(),
                    item.closedBy(),
                    item.closeReason(),
                    item.version());
        }
    }

    record AdminPayoutReconciliationRun(
            UUID id,
            LocalDate day,
            String trigger,
            Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable Integer payouts,
            @Nullable Integer found,
            @Nullable Integer open,
            @Nullable String failure) {

        static AdminPayoutReconciliationRun of(PayoutReconciliation.Run run) {
            return new AdminPayoutReconciliationRun(
                    run.id(),
                    run.day(),
                    run.trigger(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.payouts(),
                    run.found(),
                    run.open(),
                    run.failure());
        }
    }

    record AdminPayoutReconciliation(
            List<AdminPayoutReconciliationRun> runs,
            List<AdminPayoutMismatch> mismatches,
            @Nullable String nextCursor) {}

    /** Reconciles {@code day} (India's; yesterday when left out) now. */
    record RunPayoutReconciliation(
            @Nullable LocalDate day,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /** {@code state}: open (the default), fixed, accepted or all. */
    @RequiresPermission(AdminPermission.PAYOUTS_READ)
    @AdminList("payout-mismatches")
    @GetMapping("/reconciliation")
    AdminPayoutReconciliation reconciliation(
            @RequestParam(defaultValue = "open") String state,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        if (!Set.of("open", "fixed", "accepted", "all").contains(state)) {
            throw ApiProblem.invalid("state", "must be open, fixed, accepted or all.");
        }
        var page = reconciliation.list(state, sort, cursor, limit);
        return new AdminPayoutReconciliation(
                reconciliation.runs(14).stream()
                        .map(AdminPayoutReconciliationRun::of)
                        .toList(),
                page.rows().stream().map(AdminPayoutMismatch::of).toList(),
                page.nextCursor());
    }

    @RequiresPermission(AdminPermission.RECONCILIATION_RUN)
    @PostMapping("/reconciliation")
    AdminPayoutReconciliationRun reconcile(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody RunPayoutReconciliation request) {
        return AdminPayoutReconciliationRun.of(reconciliation.run(request.day(), audit.admin(admin), request.reason()));
    }

    @RequiresPermission(value = AdminPermission.RECONCILIATION_ACCEPT, record = "payout_mismatch")
    @PostMapping("/mismatches/{id}/accept")
    AdminPayoutMismatch accept(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return AdminPayoutMismatch.of(refusing(() -> reconciliation.accept(id, request.reason(), audit.admin(admin))));
    }

    // Tax deducted at source.

    private static <T> T refusing(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException refused) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, refused.getMessage());
        }
    }
}
