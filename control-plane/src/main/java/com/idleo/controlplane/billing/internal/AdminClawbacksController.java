package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Clawbacks for the admin console (ADR 0040): refunds of earnings already paid out, with what the owner's balance
 * covered, what they owe, what was recovered since, and waive, which writes off what's still owed into {@code
 * chargeback_losses}, with a reason, audited.
 */
@RestController
@RequestMapping("/admin/v1/clawbacks")
@ConditionalOnWebApplication
class AdminClawbacksController {

    /** The list, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("clawbacks", AdminPermission.CLAWBACKS_READ, false)
            .from("billing.clawbacks")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sort("amount", AdminListing.Type.NUMBER, "amount_paise")
            .sort("state", AdminListing.Type.TEXT, "state")
            .sortedBy("createdAt", true)
            .filter("state", "state = cast(:state as text)")
            .filter("tagged", "owner_id in (:tagged)")
            .csv("id", "id")
            .csv("ownerId", "owner_id")
            .csv("amountPaise", "amount_paise")
            .csv("owedPaise", "owed_paise")
            .csv("state", "state")
            .build();

    private static final Set<String> STATES = Set.of("owed", "recovered", "waived", "all");

    private final Clawbacks clawbacks;
    private final JdbcClient jdbc;
    private final AdminTagged tagged;
    private final Approvals approvals;

    AdminClawbacksController(Clawbacks clawbacks, Approvals approvals, JdbcClient jdbc, AdminTagged tagged) {
        this.jdbc = jdbc;
        this.tagged = tagged;
        this.clawbacks = clawbacks;
        this.approvals = approvals;
    }

    /**
     * @param outstandingPaise what's still owed: owed less recovered and waived
     * @param state owed, recovered or waived
     */
    record AdminClawback(
            UUID id,
            UUID ownerId,
            UUID hostId,
            UUID sessionId,
            String cause,
            long amountPaise,
            long fromBalancePaise,
            long owedPaise,
            long recoveredPaise,
            long waivedPaise,
            long outstandingPaise,
            String state,
            Instant createdAt,
            @Nullable Instant settledAt,
            @Nullable String waivedBy,
            @Nullable String waiveReason,
            long version) {}

    record AdminClawbackPage(
            List<AdminClawback> clawbacks, @Nullable String nextCursor) {}

    static AdminClawback view(Clawbacks.Clawback clawback) {
        return new AdminClawback(
                clawback.id(),
                clawback.ownerId(),
                clawback.hostId(),
                clawback.sessionId(),
                clawback.cause(),
                clawback.amountPaise(),
                clawback.fromBalancePaise(),
                clawback.owedPaise(),
                clawback.recoveredPaise(),
                clawback.waivedPaise(),
                clawback.outstandingPaise(),
                clawback.state(),
                clawback.createdAt(),
                clawback.settledAt(),
                clawback.waivedBy(),
                clawback.waiveReason(),
                clawback.version());
    }

    /** {@code state}: owed (the default), recovered, waived or all; newest first. */
    @RequiresPermission(AdminPermission.CLAWBACKS_READ)
    @GetMapping
    AdminClawbackPage list(
            @RequestParam(defaultValue = "owed") String state,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        if (!STATES.contains(state)) {
            throw ApiProblem.invalid("state", "must be owed, recovered, waived or all.");
        }
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(
                        sort, "state", state.equals("all") ? null : state, "tagged", tagged.subjects("user", tag)),
                cursor,
                limit,
                Clawbacks::clawback);
        return new AdminClawbackPage(
                page.rows().stream().map(AdminClawbacksController::view).toList(), page.nextCursor());
    }

    @RequiresPermission(value = AdminPermission.CLAWBACKS_WAIVE, record = "clawback")
    @PostMapping("/{id}/waive")
    ResponseEntity<Approvals.AdminApproval> waive(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        var clawback = clawbacks
                .find(id)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no clawback " + id + "."));
        return approvals.submit(
                admin,
                BillingGatedActions.CLAWBACK_WAIVE,
                "clawback",
                id.toString(),
                Map.of("id", id),
                List.of(new Approvals.ApprovalChange(
                        "still owed by user " + clawback.ownerId(), clawback.outstandingPaise(), 0)),
                request.reason());
    }
}
