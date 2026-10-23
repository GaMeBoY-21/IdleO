package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
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
 * Money in, for the admin console (ADR 0039): top-ups found by the renter, the top-up, or Razorpay's order or payment
 * id; the payment reconciliation's runs and items, a day run again by hand and an item accepted with a reason; and
 * chargebacks with what we hold as evidence and the date it's due in Razorpay's dashboard.
 */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnWebApplication
class AdminPaymentsController {

    private final TopUps topUps;
    private final Invoices invoices;
    private final PaymentReconciliation reconciliation;
    private final Chargebacks chargebacks;
    private final AuditTrail audit;

    AdminPaymentsController(
            TopUps topUps,
            Invoices invoices,
            PaymentReconciliation reconciliation,
            Chargebacks chargebacks,
            AuditTrail audit) {
        this.topUps = topUps;
        this.invoices = invoices;
        this.reconciliation = reconciliation;
        this.chargebacks = chargebacks;
        this.audit = audit;
    }

    record AdminPayment(
            UUID id,
            UUID userId,
            long amountPaise,
            String status,
            String orderId,
            @Nullable String paymentId,
            @Nullable String method,
            @Nullable String cardNetwork,
            @Nullable String cardLast4,
            @Nullable String failureReason,
            @Nullable String creditedBy,
            @Nullable UUID invoiceId,
            @Nullable String invoiceNumber,
            Instant createdAt,
            @Nullable Instant paidAt) {}

    record AdminPaymentList(List<AdminPayment> payments) {}

    record AdminPaymentMismatch(
            UUID id,
            String kind,
            String subject,
            @Nullable String paymentId,
            @Nullable UUID topUpId,
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
            @Nullable String closeReason) {}

    record AdminPaymentReconciliationRun(
            UUID id,
            LocalDate day,
            String trigger,
            Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable Integer payments,
            @Nullable Integer refunds,
            @Nullable Integer found,
            @Nullable Integer open,
            @Nullable String failure) {}

    record AdminPaymentReconciliation(
            List<AdminPaymentReconciliationRun> runs, List<AdminPaymentMismatch> mismatches) {}

    /** Runs {@code day} (India's; today when left out) again now. */
    record RunPaymentReconciliation(
            @Nullable LocalDate day,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    record AdminChargebackSession(UUID sessionId, long paidFromThisPaymentPaise) {}

    record AdminChargeback(
            UUID id,
            String disputeId,
            String paymentId,
            @Nullable UUID userId,
            long amountPaise,
            String currency,
            @Nullable String reasonCode,
            @Nullable String phase,
            String status,
            @Nullable Instant respondBy,
            long deductedPaise,
            long frozenPaise,
            @Nullable Long lostPaise,
            @Nullable Long releasedPaise,
            @Nullable String outcome,
            Instant openedAt,
            @Nullable Instant settledAt,
            @Nullable AdminPayment payment,
            List<AdminChargebackSession> sessions) {}

    record AdminChargebackList(List<AdminChargeback> chargebacks) {}

    /** {@code query}: a renter's id, a top-up's id, or Razorpay's order or payment id. */
    @GetMapping("/payments")
    AdminPaymentList payments(@RequestParam String query) {
        return new AdminPaymentList(
                topUps.search(query, 50).stream().map(this::payment).toList());
    }

    /** {@code state}: open (the default), fixed, accepted or all. */
    @GetMapping("/payments/reconciliation")
    AdminPaymentReconciliation reconciliation(@RequestParam(defaultValue = "open") String state) {
        return new AdminPaymentReconciliation(
                reconciliation.runs(14).stream()
                        .map(AdminPaymentsController::run)
                        .toList(),
                reconciliation.mismatches(state, 200).stream()
                        .map(AdminPaymentsController::mismatch)
                        .toList());
    }

    @PostMapping("/payments/reconciliation")
    AdminPaymentReconciliationRun runReconciliation(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody RunPaymentReconciliation request) {
        return run(refusing(() -> reconciliation.run(request.day(), audit.admin(admin), request.reason())));
    }

    @PostMapping("/payments/mismatches/{id}/accept")
    AdminPaymentMismatch accept(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        reconciliation
                .mismatch(id)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no mismatch " + id + "."));
        return mismatch(refusing(() -> reconciliation.accept(id, request.reason(), audit.admin(admin))));
    }

    /** The open ones by the date evidence is due (the default), or {@code all}, the newest first. */
    @GetMapping("/chargebacks")
    AdminChargebackList chargebacks(@RequestParam(defaultValue = "open") String state) {
        return new AdminChargebackList(chargebacks.list(!state.equals("all"), 100).stream()
                .map(this::chargeback)
                .toList());
    }

    @GetMapping("/chargebacks/{id}")
    AdminChargeback chargeback(@PathVariable UUID id) {
        return chargeback(chargebacks
                .find(id)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no chargeback " + id + ".")));
    }

    private AdminChargeback chargeback(Chargebacks.Chargeback chargeback) {
        var payment = chargeback.topUpId() == null
                ? null
                : topUps.find(chargeback.topUpId()).map(this::payment).orElse(null);
        return new AdminChargeback(
                chargeback.id(),
                chargeback.disputeId(),
                chargeback.paymentId(),
                chargeback.userId(),
                chargeback.amount().value(),
                chargeback.currency(),
                chargeback.reasonCode(),
                chargeback.phase(),
                chargeback.status(),
                chargeback.respondBy(),
                chargeback.deducted().value(),
                chargeback.frozen().value(),
                chargeback.lost() == null ? null : chargeback.lost().value(),
                chargeback.released() == null ? null : chargeback.released().value(),
                chargeback.outcome(),
                chargeback.openedAt(),
                chargeback.settledAt(),
                payment,
                chargebacks.spentFrom(chargeback).stream()
                        .map(spent -> new AdminChargebackSession(
                                spent.sessionId(), spent.amount().value()))
                        .toList());
    }

    private AdminPayment payment(TopUps.TopUp topUp) {
        var invoice = invoices.ofTopUp(topUp.id());
        return new AdminPayment(
                topUp.id(),
                topUp.userId(),
                topUp.amount().value(),
                topUp.status().dbValue(),
                topUp.orderId(),
                topUp.paymentId(),
                topUp.method(),
                topUp.cardNetwork(),
                topUp.cardLast4(),
                topUp.failureReason(),
                topUp.creditedBy(),
                invoice.map(Invoices.Invoice::id).orElse(null),
                invoice.map(Invoices.Invoice::number).orElse(null),
                topUp.createdAt(),
                topUp.paidAt());
    }

    private static AdminPaymentReconciliationRun run(PaymentReconciliation.Run run) {
        return new AdminPaymentReconciliationRun(
                run.id(),
                run.day(),
                run.trigger(),
                run.startedAt(),
                run.finishedAt(),
                run.payments(),
                run.refunds(),
                run.found(),
                run.open(),
                run.failure());
    }

    private static AdminPaymentMismatch mismatch(PaymentReconciliation.Mismatch item) {
        return new AdminPaymentMismatch(
                item.id(),
                item.kind(),
                item.subject(),
                item.paymentId(),
                item.topUpId(),
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

    private static <T> T refusing(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException refused) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, refused.getMessage());
        }
    }
}
