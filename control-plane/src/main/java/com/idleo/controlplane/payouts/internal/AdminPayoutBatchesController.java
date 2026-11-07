package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
 * Payout batches for the console (ADR 0055): a batch and its hosts as previewed (each one's gross, what's recovered and
 * held, the tax, the fee, the net and the masked UPI ID, or why they're left out) with each payout's status once it's
 * released; the batch report for the CA (the items' CSV); refreshing a preview; and releasing it, one approved action.
 */
@RestController
@RequestMapping("/admin/v1/payouts")
@ConditionalOnWebApplication
class AdminPayoutBatchesController {

    static final String RELEASE_BATCH = "payout_batch.release";

    private final PayoutRuns runs;
    private final Approvals approvals;
    private final JdbcClient jdbc;

    AdminPayoutBatchesController(PayoutRuns runs, Approvals approvals, JdbcClient jdbc) {
        this.runs = runs;
        this.approvals = approvals;
        this.jdbc = jdbc;
    }

    /** One host in a batch: the preview's figures, why they're left out (in words), and the payout and its status. */
    record AdminBatchItem(
            UUID userId,
            long grossPaise,
            long recoveredPaise,
            long heldPaise,
            long tdsPaise,
            long feePaise,
            long netPaise,
            @Nullable String upiMasked,
            @Nullable String leftOut,
            @Nullable String leftOutWords,
            @Nullable UUID payoutId,
            @Nullable String payoutStatus) {}

    record AdminBatchItems(
            List<AdminBatchItem> items, @Nullable String nextCursor) {}

    @RequiresPermission(AdminPermission.PAYOUTS_READ)
    @GetMapping("/batches/{id}")
    AdminPayoutsController.AdminPayoutRun batch(@PathVariable UUID id) {
        return runs.find(id)
                .map(AdminPayoutsController.AdminPayoutRun::of)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such batch."));
    }

    /** A batch's hosts, the paid first; its CSV is the batch report for the CA. */
    @RequiresPermission(AdminPermission.PAYOUTS_READ)
    @AdminList("payout-batch-items")
    @GetMapping("/batch-items")
    AdminBatchItems items(
            @RequestParam(required = false) @Nullable UUID batch,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = ITEMS.page(jdbc, AdminListing.Asked.of(sort, "batch", batch), cursor, limit, (rs, n) -> {
            var leftOut = rs.getString("left_out");
            return new AdminBatchItem(
                    rs.getObject("user_id", UUID.class),
                    rs.getLong("gross_paise"),
                    rs.getLong("recovered_paise"),
                    rs.getLong("held_paise"),
                    rs.getLong("tds_paise"),
                    rs.getLong("fee_paise"),
                    rs.getLong("net_paise"),
                    rs.getString("upi_masked"),
                    leftOut,
                    leftOut == null ? null : PayoutService.NotPaid.valueOf(leftOut.toUpperCase(Locale.ROOT)).words,
                    rs.getObject("payout_id", UUID.class),
                    rs.getString("payout_status"));
        });
        return new AdminBatchItems(page.rows(), page.nextCursor());
    }

    /** A batch's hosts, their orders and CSV (ADR 0052): the batch report. */
    static final AdminListing ITEMS = AdminListing.of("payout-batch-items", AdminPermission.PAYOUTS_READ, true)
            .select("i.*, p.status as payout_status")
            .from("payouts.batch_items i left join payouts.payouts p on p.id = i.payout_id")
            .id("i.user_id")
            .sort("net", AdminListing.Type.NUMBER, "i.net_paise")
            .sortedBy("net", true)
            .filter("batch", "i.run_id = cast(:batch as uuid)")
            .csv("userId", "i.user_id")
            .csv("grossPaise", "i.gross_paise")
            .csv("recoveredPaise", "i.recovered_paise")
            .csv("heldPaise", "i.held_paise")
            .csv("tdsPaise", "i.tds_paise")
            .csv("feePaise", "i.fee_paise")
            .csv("netPaise", "i.net_paise")
            .csv("upi", "i.upi_masked")
            .csv("leftOut", "i.left_out")
            .csv("payoutId", "i.payout_id")
            .csv("payoutStatus", "p.status")
            .build();

    /** The preview made again from now, for a batch not yet released: what release will pay. */
    @RequiresPermission(value = AdminPermission.PAYOUTS_RUN, record = "payout_batch")
    @PostMapping("/batches/{id}/refresh")
    AdminPayoutsController.AdminPayoutRun refresh(@PathVariable UUID id, @Valid @RequestBody AdminReason request) {
        var run = runs.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such batch."));
        try {
            return AdminPayoutsController.AdminPayoutRun.of(runs.prepare(run));
        } catch (PayoutRuns.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        }
    }

    /**
     * Asks to release a batch: another admin approves, with a factor. It pays each host as previewed, once; if any
     * host's figures changed in the meantime it pays nobody and the preview must be refreshed.
     */
    @RequiresPermission(value = AdminPermission.PAYOUTS_RELEASE, record = "payout_batch")
    @PostMapping("/batches/{id}/release")
    ResponseEntity<Approvals.AdminApproval> release(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        var run = runs.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such batch."));
        if (run.released()) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This batch is already released.");
        }
        return approvals.submit(
                admin,
                RELEASE_BATCH,
                "payout_batch",
                id.toString(),
                Map.of("id", id),
                List.of(
                        new Approvals.ApprovalChange(
                                "payouts in the batch of " + run.payday(),
                                0,
                                run.payouts() == null ? 0 : run.payouts()),
                        new Approvals.ApprovalChange(
                                "paid out, net, in paise", 0, run.totalPaise() == null ? 0 : run.totalPaise())),
                request.reason());
    }
}
