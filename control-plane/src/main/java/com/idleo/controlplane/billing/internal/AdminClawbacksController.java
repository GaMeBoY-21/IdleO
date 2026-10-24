package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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

    private static final Set<String> STATES = Set.of("owed", "recovered", "waived", "all");

    private final Clawbacks clawbacks;
    private final AuditTrail audit;

    AdminClawbacksController(Clawbacks clawbacks, AuditTrail audit) {
        this.clawbacks = clawbacks;
        this.audit = audit;
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
            @Nullable String waiveReason) {}

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
                clawback.waiveReason());
    }

    /** {@code state}: owed (the default), recovered, waived or all; newest first. */
    @GetMapping
    AdminClawbackPage list(
            @RequestParam(defaultValue = "owed") String state,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        if (!STATES.contains(state)) {
            throw ApiProblem.invalid("state", "must be owed, recovered, waived or all.");
        }
        int size = AdminPages.limit(limit);
        var rows = clawbacks.list(state, AdminPages.after(cursor), size + 1);
        var shown = rows.size() > size ? rows.subList(0, size) : rows;
        return new AdminClawbackPage(
                shown.stream().map(AdminClawbacksController::view).toList(),
                rows.size() > size ? AdminPages.cursorAfter(shown.getLast().id()) : null);
    }

    @PostMapping("/{id}/waive")
    AdminClawback waive(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        if (clawbacks.find(id).isEmpty()) {
            throw ApiProblem.of(Problem.NOT_FOUND, "There's no clawback " + id + ".");
        }
        try {
            return view(clawbacks.waive(id, request.reason(), audit.admin(admin)));
        } catch (IllegalArgumentException refused) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, refused.getMessage());
        }
    }
}
