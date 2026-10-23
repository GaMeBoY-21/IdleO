package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
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
 * Refunds of bought credit for the admin console (ADR 0039): the waiting requests, oldest first, each with what the
 * renter can have back now and its ticket; approve an amount (at most that) or decline, with a reason, audited. The
 * money goes back only to the payments it came from.
 */
@RestController
@RequestMapping("/admin/v1/refunds")
@ConditionalOnWebApplication
class AdminRefundsController {

    private final CreditRefunds refunds;
    private final AuditTrail audit;

    AdminRefundsController(CreditRefunds refunds, AuditTrail audit) {
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
            List<AdminCreditRefundPart> parts) {}

    record AdminCreditRefundList(List<AdminCreditRefund> refunds) {}

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
                        .toList());
    }

    /** {@code status}: requested (the default), refunding, refunded, failed, declined or all. */
    @GetMapping
    AdminCreditRefundList list(@RequestParam(defaultValue = "requested") String status) {
        return new AdminCreditRefundList(
                refunds.list(status, 100).stream().map(this::view).toList());
    }

    @GetMapping("/{id}")
    AdminCreditRefund get(@PathVariable UUID id) {
        return view(found(id));
    }

    @PostMapping("/{id}/approve")
    AdminCreditRefund approve(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody ApproveCreditRefund request) {
        found(id);
        return view(refusing(
                () -> refunds.approve(id, Paise.of(request.amountPaise()), request.reason(), audit.admin(admin))));
    }

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
