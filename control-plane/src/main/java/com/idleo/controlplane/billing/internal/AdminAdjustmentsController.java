package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Manual adjustments for the console (ADR 0055): asking for one, which goes to another admin with its legs as the diff
 * and posts only as approved; the list of those posted; one with its legs and its reversal; and asking to reverse one.
 */
@RestController
@RequestMapping("/admin/v1/adjustments")
@ConditionalOnWebApplication
class AdminAdjustmentsController {

    private final Adjustments adjustments;
    private final Approvals approvals;
    private final JdbcClient jdbc;

    AdminAdjustmentsController(Adjustments adjustments, Approvals approvals, JdbcClient jdbc) {
        this.adjustments = adjustments;
        this.approvals = approvals;
        this.jdbc = jdbc;
    }

    /** One leg: an account, its owner where the account needs one, an amount in paise and a side. */
    record AdminAdjustmentLeg(
            @NotBlank String account,
            @Nullable UUID ownerId,
            @Positive long amountPaise,

            @NotBlank @Schema(allowableValues = {"debit", "credit"})
            String direction) {}

    /**
     * An adjustment asked for: its template ({@code journal}, {@code goodwill_credit}, {@code fee_correction} or {@code
     * write_off}), its legs, why, its reference (a ticket or a reconciliation item) and, for a correction, the closed
     * month (YYYY-MM) it corrects.
     */
    record AdminAdjustmentRequest(
            @NotBlank @Schema(allowableValues = {"journal", "goodwill_credit", "fee_correction", "write_off"})
            String template,

            @NotNull @Size(min = 2, max = 20) List<@Valid AdminAdjustmentLeg> legs,
            @NotBlank @Size(min = 5, max = 500) String reason,
            @NotBlank @Size(max = 200) String reference,
            @Nullable String correctsMonth) {}

    /** Asking to reverse an adjustment: why, and its reference. */
    record AdminAdjustmentReversal(
            @NotBlank @Size(min = 5, max = 500) String reason,
            @NotBlank @Size(max = 200) String reference) {}

    record AdminAdjustment(
            UUID id,
            String template,
            String reason,
            String reference,
            @Nullable String correctsMonth,
            @Nullable UUID reversesId,
            @Nullable UUID reversedById,
            UUID approvalId,
            UUID txnId,
            String requestedBy,
            Instant createdAt,
            List<AdminAdjustmentLeg> legs,
            long version) {}

    record AdminAdjustmentPage(
            List<AdminAdjustment> adjustments, @Nullable String nextCursor) {}

    /**
     * Asks for an adjustment. It's checked now (the legs balance, the accounts may be adjusted, the template's shape
     * holds), then goes to another admin, who sees every leg; it posts only once approved.
     */
    @RequiresPermission(AdminPermission.MONEY_ADJUST)
    @PostMapping
    ResponseEntity<Approvals.AdminApproval> ask(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminAdjustmentRequest body) {
        var request = request(body);
        refusing(() -> adjustments.check(request));
        var params = new LinkedHashMap<String, Object>();
        params.put("template", request.template().dbValue());
        params.put("legs", body.legs());
        params.put("reference", request.reference());
        params.put("correctsMonth", body.correctsMonth());
        return approvals.submit(
                admin, BillingGatedActions.ADJUSTMENT, null, null, params, changes(request.legs()), request.reason());
    }

    /** Asks to reverse an adjustment: the same legs the other way round, approved like any other. */
    @RequiresPermission(value = AdminPermission.MONEY_ADJUST, record = "adjustment")
    @PostMapping("/{id}/reverse")
    ResponseEntity<Approvals.AdminApproval> reverse(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminAdjustmentReversal body) {
        var original = adjustments
                .find(id)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no adjustment " + id + "."));
        if (original.template() == Adjustments.Template.REVERSAL) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "A reversal isn't reversed; make a new adjustment.");
        }
        if (adjustments.reversalOf(id).isPresent()) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This adjustment is already reversed.");
        }
        var opposite = adjustments.legs(original.txnId()).stream()
                .map(leg -> new Adjustments.Leg(
                        leg.account(),
                        leg.ownerId(),
                        leg.amount(),
                        leg.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT))
                .toList();
        return approvals.submit(
                admin,
                BillingGatedActions.ADJUSTMENT_REVERSE,
                "adjustment",
                id.toString(),
                Map.of("id", id, "reference", body.reference().strip()),
                changes(opposite),
                body.reason());
    }

    /** The adjustments posted, newest first; {@code template} keeps one kind. */
    @RequiresPermission(AdminPermission.LEDGER_READ)
    @AdminList("adjustments")
    @GetMapping
    AdminAdjustmentPage list(
            @RequestParam(required = false) @Nullable String template,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(sort, "template", template),
                cursor,
                limit,
                (rs, n) -> rs.getObject("id", UUID.class));
        return new AdminAdjustmentPage(
                page.rows().stream().map(this::adjustment).toList(), page.nextCursor());
    }

    /** One adjustment, with its legs and, if it has one, its reversal. */
    @RequiresPermission(AdminPermission.LEDGER_READ)
    @GetMapping("/{id}")
    AdminAdjustment one(@PathVariable UUID id) {
        return adjustment(id);
    }

    /** The adjustments, their orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("adjustments", AdminPermission.LEDGER_READ, false)
            .noBulk("Its rows are money, and money is never bulk.")
            .from("billing.adjustments")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sortedBy("createdAt", true)
            .filter("template", "template = cast(:template as text)")
            .csv("id", "id")
            .csv("template", "template")
            .csv("reason", "reason")
            .csv("reference", "reference")
            .csv("correctsMonth", "corrects_month")
            .csv("reversesId", "reverses_id")
            .csv("approvalId", "approval_id")
            .csv("txnId", "txn_id")
            .csv("requestedBy", "requested_by")
            .csv("createdAt", "created_at")
            .build();

    private AdminAdjustment adjustment(UUID id) {
        var found = adjustments
                .find(id)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no adjustment " + id + "."));
        var reversal = adjustments.reversalOf(id);
        return new AdminAdjustment(
                found.id(),
                found.template().dbValue(),
                found.reason(),
                found.reference(),
                found.correctsMonth() == null ? null : found.correctsMonth().toString(),
                found.reversesId(),
                reversal.map(Adjustments.Adjustment::id).orElse(null),
                found.approvalId(),
                found.txnId(),
                found.requestedBy(),
                found.createdAt(),
                adjustments.legs(found.txnId()).stream()
                        .map(leg -> new AdminAdjustmentLeg(
                                leg.account().dbValue,
                                leg.ownerId(),
                                leg.amount().value(),
                                leg.direction().dbValue))
                        .toList(),
                reversal.isPresent() ? 2 : 1);
    }

    /** Each leg as a line of the approval's diff: what it moves, and by how much in paise. */
    private static List<Approvals.ApprovalChange> changes(List<Adjustments.Leg> legs) {
        return legs.stream()
                .map(leg -> new Approvals.ApprovalChange(
                        leg.direction().dbValue + " " + leg.account().dbValue
                                + (leg.ownerId() == null ? "" : " of " + leg.ownerId()),
                        0,
                        leg.amount().value()))
                .toList();
    }

    private static Adjustments.Request request(AdminAdjustmentRequest body) {
        Adjustments.Template template;
        try {
            template = Adjustments.Template.fromDb(body.template().strip());
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("template", "isn't one of the adjustments' templates.");
        }
        if (template == Adjustments.Template.REVERSAL) {
            throw ApiProblem.invalid("template", "a reversal is asked for from the adjustment it reverses.");
        }
        var legs = body.legs().stream()
                .map(leg -> {
                    Account account;
                    try {
                        account = Account.fromDb(leg.account().strip());
                    } catch (IllegalArgumentException e) {
                        throw ApiProblem.invalid("legs", leg.account() + " isn't one of the ledger's accounts.");
                    }
                    Direction direction;
                    try {
                        direction = Direction.fromDb(leg.direction().strip());
                    } catch (IllegalArgumentException e) {
                        throw ApiProblem.invalid("legs", "a leg is a debit or a credit.");
                    }
                    return new Adjustments.Leg(account, leg.ownerId(), Paise.of(leg.amountPaise()), direction);
                })
                .toList();
        return new Adjustments.Request(
                template, legs, body.reason(), body.reference(), correctsMonth(body.correctsMonth()));
    }

    private static @Nullable YearMonth correctsMonth(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(value.strip());
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiProblem.invalid("correctsMonth", "is a month such as 2026-10.");
        }
    }

    private static void refusing(Runnable check) {
        try {
            check.run();
        } catch (Adjustments.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        }
    }
}
