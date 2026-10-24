package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.FinancialYear;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
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

    AdminPayoutsController(
            PayoutService payouts,
            PayoutRuns runs,
            BalanceCheck balance,
            Pans pans,
            PayoutMethods methods,
            PayoutReconciliation reconciliation,
            AuditTrail audit,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
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
            @Nullable String failure) {

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
                    run.failure());
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

    /**
     * @param notPaid why no payout was made: NO_METHOD, ON_HOLD, NO_PAN, IN_PROGRESS or TOO_LITTLE
     */
    record AdminRetryOutcome(
            @Nullable AdminPayout payout, @Nullable String notPaid) {}

    @GetMapping
    AdminPayouts list(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) @Nullable UUID run,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        if (!STATUSES.contains(status)) {
            throw ApiProblem.invalid("status", "isn't a payout status.");
        }
        int size = AdminPages.limit(limit);
        var rows = payouts.list(status, run, AdminPages.after(cursor), size + 1);
        var shown = rows.size() > size ? rows.subList(0, size) : rows;
        return new AdminPayouts(
                runs.latest(10).stream().map(AdminPayoutRun::of).toList(),
                shown.stream().map(AdminPayout::of).toList(),
                rows.size() > size ? AdminPages.cursorAfter(shown.getLast().id()) : null,
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

    /** Starts a run now, outside the schedule: everyone who can be paid at least the weekly minimum. */
    @PostMapping("/runs")
    AdminPayoutRun run(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminReason request) {
        var run = runs.startNow(request.reason(), audit.admin(admin));
        return AdminPayoutRun.of(runs.pay(run, Paise.of(runs.minimum())));
    }

    /** Pays one person now (a retry after a failed payout), whatever the weekly minimum. */
    @PostMapping("/retry")
    AdminRetryOutcome retry(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody RetryPayout request) {
        var outcome = runs.payNow(request.userId(), "retry", request.reason(), audit.admin(admin));
        return new AdminRetryOutcome(
                outcome.payout() == null ? null : AdminPayout.of(outcome.payout()),
                outcome.notPaid() == null ? null : outcome.notPaid().name());
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
            @Nullable String reason) {

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
                    pan.reason());
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
            @Nullable Instant confirmedAt) {}

    record AdminVerifications(List<AdminPanReview> pans, List<AdminUpiReview> upiIds) {}

    record AdminPanReveal(UUID userId, String pan) {}

    @GetMapping("/verifications")
    AdminVerifications verifications() {
        return new AdminVerifications(
                pans.inReview(100).stream().map(AdminPanReview::of).toList(),
                methods.reviews(100).stream()
                        .map(method -> new AdminUpiReview(
                                method.id(),
                                method.userId(),
                                method.vpa(),
                                method.registeredName(),
                                pans.find(method.userId()).map(Pans.Pan::name).orElse(null),
                                method.nameMatchScore(),
                                method.state(),
                                method.checkedAt(),
                                method.confirmedAt()))
                        .toList());
    }

    @PostMapping("/verifications/pans/{userId}/verify")
    AdminPanReview verifyPan(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminReason request) {
        return AdminPanReview.of(refusing(() -> pans.decide(userId, true, request.reason(), audit.admin(admin))));
    }

    @PostMapping("/verifications/pans/{userId}/reject")
    AdminPanReview rejectPan(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminReason request) {
        return AdminPanReview.of(refusing(() -> pans.decide(userId, false, request.reason(), audit.admin(admin))));
    }

    /** The whole PAN, read with a reason, audited. */
    @PostMapping("/verifications/pans/{userId}/reveal")
    AdminPanReveal revealPan(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminReason request) {
        return pans.reveal(userId, request.reason(), audit.admin(admin))
                .map(pan -> new AdminPanReveal(userId, pan))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "This person has given no PAN."));
    }

    @PostMapping("/verifications/upi-ids/{id}/approve")
    AdminUpiReview approveUpi(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return upi(refusing(() -> methods.decide(id, true, request.reason(), audit.admin(admin))));
    }

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
                method.confirmedAt());
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
            @Nullable String closeReason) {

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
                    item.closeReason());
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

    record AdminPayoutReconciliation(List<AdminPayoutReconciliationRun> runs, List<AdminPayoutMismatch> mismatches) {}

    /** Reconciles {@code day} (India's; yesterday when left out) now. */
    record RunPayoutReconciliation(
            @Nullable LocalDate day,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /** {@code state}: open (the default), fixed, accepted or all. */
    @GetMapping("/reconciliation")
    AdminPayoutReconciliation reconciliation(@RequestParam(defaultValue = "open") String state) {
        if (!Set.of("open", "fixed", "accepted", "all").contains(state)) {
            throw ApiProblem.invalid("state", "must be open, fixed, accepted or all.");
        }
        return new AdminPayoutReconciliation(
                reconciliation.runs(14).stream()
                        .map(AdminPayoutReconciliationRun::of)
                        .toList(),
                reconciliation.list(state, 200).stream()
                        .map(AdminPayoutMismatch::of)
                        .toList());
    }

    @PostMapping("/reconciliation")
    AdminPayoutReconciliationRun reconcile(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody RunPayoutReconciliation request) {
        return AdminPayoutReconciliationRun.of(reconciliation.run(request.day(), audit.admin(admin), request.reason()));
    }

    @PostMapping("/mismatches/{id}/accept")
    AdminPayoutMismatch accept(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return AdminPayoutMismatch.of(refusing(() -> reconciliation.accept(id, request.reason(), audit.admin(admin))));
    }

    // Tax deducted at source.

    /** A financial year's quarter, {@code 2026-27-Q3} (October to December 2026), and why it's exported. */
    record TdsExport(
            @NotBlank @Size(min = 10, max = 10) String quarter,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /**
     * The quarter's tax deducted at source as CSV, for the CA to file: each payout that didn't come back, deducted in
     * the quarter, with the host's whole PAN and name as on it, the section and rate, the gross, the tax, the net and
     * the dates. Audited: it holds every PAN.
     */
    @PostMapping(value = "/tds-export", produces = "text/csv")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "The quarter's deductions as CSV",
            content =
                    @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "text/csv",
                            schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string")))
    ResponseEntity<byte[]> tdsExport(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody TdsExport request) {
        if (!request.quarter().matches("[0-9]{4}-[0-9]{2}-Q[1-4]")) {
            throw ApiProblem.invalid("quarter", "is a financial year and a quarter, like 2026-27-Q3.");
        }
        FinancialYear year;
        try {
            year = FinancialYear.parse(request.quarter().substring(0, 7));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("quarter", e.getMessage() + ".");
        }
        int quarter = request.quarter().charAt(9) - '0';
        var start = LocalDate.of(year.startYear(), 4, 1).plusMonths(3L * (quarter - 1));
        var from = start.atStartOfDay(FinancialYear.INDIA).toInstant();
        var to = start.plusMonths(3).atStartOfDay(FinancialYear.INDIA).toInstant();
        record Row(
                UUID payoutId,
                UUID userId,
                String section,
                int basisPoints,
                long gross,
                long tds,
                long net,
                String status,
                Instant createdAt,
                @Nullable Instant processedAt) {}
        var rows = jdbc.sql("""
                        select id, user_id, tds_section, tds_basis_points, gross_paise, tds_paise, net_paise, status,
                               created_at, processed_at
                        from payouts.payouts
                        where created_at >= :from and created_at < :to and status not in (:returned)
                        order by created_at, id
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("returned", TaxDeduction.RETURNED)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("tds_section"),
                        rs.getInt("tds_basis_points"),
                        rs.getLong("gross_paise"),
                        rs.getLong("tds_paise"),
                        rs.getLong("net_paise"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("processed_at") == null
                                ? null
                                : rs.getTimestamp("processed_at").toInstant()))
                .list();
        var day = DateTimeFormatter.ISO_LOCAL_DATE;
        var csv = new StringBuilder(
                "payout,pan,name_on_pan,holder,section,rate_percent,gross_paise,tds_paise,net_paise,deducted_on,"
                        + "paid_on,status\n");
        long total = 0;
        for (var row : rows) {
            var pan = pans.find(row.userId()).orElse(null);
            csv.append(row.payoutId())
                    .append(',')
                    .append(pans.full(row.userId()).orElse(""))
                    .append(',')
                    .append(Statements.quote(pan == null ? "" : pan.name()))
                    .append(',')
                    .append(pan == null ? "" : pan.holder())
                    .append(',')
                    .append(Statements.quote(row.section()))
                    .append(',')
                    .append(java.math.BigDecimal.valueOf(row.basisPoints(), 2).toPlainString())
                    .append(',')
                    .append(row.gross())
                    .append(',')
                    .append(row.tds())
                    .append(',')
                    .append(row.net())
                    .append(',')
                    .append(day.format(ZonedDateTime.ofInstant(row.createdAt(), FinancialYear.INDIA)))
                    .append(',')
                    .append(
                            row.processedAt() == null
                                    ? ""
                                    : day.format(ZonedDateTime.ofInstant(row.processedAt(), FinancialYear.INDIA)))
                    .append(',')
                    .append(row.status())
                    .append('\n');
            total += row.tds();
        }
        long deducted = total;
        transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                audit.admin(admin),
                "tds.export",
                "tds_quarter",
                request.quarter(),
                request.reason(),
                null,
                Map.of("payouts", rows.size(), "tdsPaise", deducted))));
        return OwnerPayoutsController.file(
                csv.toString().getBytes(StandardCharsets.UTF_8),
                new MediaType("text", "csv"),
                "idleo-tds-" + request.quarter() + ".csv");
    }

    private static <T> T refusing(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException refused) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, refused.getMessage());
        }
    }
}
