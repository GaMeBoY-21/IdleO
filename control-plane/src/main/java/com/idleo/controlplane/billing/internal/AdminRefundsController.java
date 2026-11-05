package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Refunds of bought credit for the admin console (ADR 0039): the waiting requests, oldest first, each with what the
 * renter can have back now and its ticket; approve an amount (at most that) or decline, with a reason, audited. The
 * money goes back only to the payments it came from.
 */
@RestController
@RequestMapping("/admin/v1/refunds")
@ConditionalOnWebApplication
class AdminRefundsController {

    private final CreditRefunds refunds;
    private final JdbcClient jdbc;
    private final AdminTagged tagged;
    private final AuditTrail audit;
    private final Approvals approvals;

    AdminRefundsController(
            CreditRefunds refunds, AuditTrail audit, Approvals approvals, JdbcClient jdbc, AdminTagged tagged) {
        this.jdbc = jdbc;
        this.tagged = tagged;
        this.approvals = approvals;
        this.refunds = refunds;
        this.audit = audit;
    }

    record AdminCreditRefundPart(
            UUID id,
            String paymentId,
            long amountPaise,
            String status,
            @Nullable String razorpayRefundId,
            @Nullable String failureReason) {}

    record AdminCreditRefund(
            UUID id,
            UUID userId,
            String cause,
            String status,
            @Nullable Long requestedPaise,
            @Nullable Long approvedPaise,
            long refundablePaise,
            @Nullable String note,
            @Nullable Long ticketNumber,
            @Nullable UUID ticketId,
            @Nullable String decidedBy,
            @Nullable String decision,
            Instant requestedAt,
            @Nullable Instant finishedAt,
            List<AdminCreditRefundPart> parts,
            long version) {}

    record AdminCreditRefundList(
            List<AdminCreditRefund> refunds, @Nullable String nextCursor) {}

    /** Approves {@code amountPaise} of the request: at most what the renter can have back now. */
    record ApproveCreditRefund(
            @Positive long amountPaise,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    AdminCreditRefund view(CreditRefunds.Refund refund) {
        return new AdminCreditRefund(
                refund.id(),
                refund.userId(),
                refund.cause(),
                refund.status(),
                refund.requested() == null ? null : refund.requested().value(),
                refund.approved() == null ? null : refund.approved().value(),
                refunds.refundable(refund.userId()).value(),
                refund.note(),
                refund.ticketNumber(),
                refund.ticketId(),
                refund.decidedBy(),
                refund.decision(),
                refund.requestedAt(),
                refund.finishedAt(),
                refunds.parts(refund.id()).stream()
                        .map(part -> new AdminCreditRefundPart(
                                part.id(),
                                part.paymentId(),
                                part.amount().value(),
                                part.status(),
                                part.razorpayRefundId(),
                                part.failureReason()))
                        .toList(),
                refund.version());
    }

    /** The list, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("refunds", AdminPermission.REFUNDS_READ, true)
            .from("billing.credit_refunds")
            .sort("requestedAt", AdminListing.Type.TIME, "requested_at")
            .sort("status", AdminListing.Type.TEXT, "status")
            .sortedBy("requestedAt", true)
            .filter("status", "status = cast(:status as text)")
            .filter("user", "user_id = cast(:user as uuid)")
            .filter("tagged", "user_id in (:tagged)")
            .csv("id", "id")
            .csv("userId", "user_id")
            .csv("cause", "cause")
            .csv("requestedPaise", "requested_paise")
            .csv("approvedPaise", "approved_paise")
            .csv("status", "status")
            .csv("requestedAt", "requested_at")
            .csv("finishedAt", "finished_at")
            .build();

    /** {@code status}: requested (the default), refunding, refunded, failed, declined or all. */
    @RequiresPermission(AdminPermission.REFUNDS_READ)
    @GetMapping
    AdminCreditRefundList list(
            @RequestParam(defaultValue = "requested") String status,
            @RequestParam(required = false) @Nullable UUID user,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(
                        sort,
                        "status",
                        status.equals("all") || user != null ? null : status,
                        "user",
                        user,
                        "tagged",
                        tagged.subjects("user", tag)),
                cursor,
                limit,
                CreditRefunds::refund);
        return new AdminCreditRefundList(page.rows().stream().map(this::view).toList(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.REFUNDS_READ)
    @GetMapping("/{id}")
    AdminCreditRefund get(@PathVariable UUID id) {
        return view(found(id));
    }

    @RequiresPermission(value = AdminPermission.REFUNDS_APPROVE, record = "credit_refund")
    @PostMapping("/{id}/approve")
    @ApiResponse(
            responseCode = "200",
            description = "The refund",
            content = @Content(schema = @Schema(implementation = AdminCreditRefund.class)))
    @ApiResponse(
            responseCode = "202",
            description = "Above the limit, so it goes to another admin",
            content = @Content(schema = @Schema(implementation = Approvals.AdminApproval.class)))
    ResponseEntity<?> approve(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody ApproveCreditRefund request) {
        var refund = found(id);
        var amount = Paise.of(request.amountPaise());
        if (approvals.refundNeedsApproval(amount)) {
            return approvals.submit(
                    admin,
                    BillingGatedActions.REFUND_APPROVE,
                    "credit_refund",
                    id.toString(),
                    Map.of("id", id, "amountPaise", amount.value()),
                    List.of(new Approvals.ApprovalChange(
                            "refund to user " + refund.userId(), refund.status(), amount.value())),
                    request.reason());
        }
        return ResponseEntity.ok(
                view(refusing(() -> refunds.approve(id, amount, request.reason(), audit.admin(admin)))));
    }

    @RequiresPermission(value = AdminPermission.REFUNDS_DECLINE, record = "credit_refund")
    @PostMapping("/{id}/decline")
    AdminCreditRefund decline(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        found(id);
        return view(refusing(() -> refunds.decline(id, request.reason(), audit.admin(admin))));
    }

    private CreditRefunds.Refund found(UUID id) {
        return refunds.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no refund " + id + "."));
    }

    private static <T> T refusing(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException refused) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, refused.getMessage());
        }
    }
}
