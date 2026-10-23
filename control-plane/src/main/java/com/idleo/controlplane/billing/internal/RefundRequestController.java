package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.OpenToHeldAccounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Refund unused credit" (ADR 0039): a renter asks for bought credit back, to where they paid from; a money ticket
 * opens and an admin decides. A held account may ask too: its money is still its own.
 */
@RestController
@RequestMapping("/v1/wallet/refund-requests")
class RefundRequestController {

    private final CreditRefunds refunds;

    RefundRequestController(CreditRefunds refunds) {
        this.refunds = refunds;
    }

    /** {@code amountPaise} null asks for all the bought credit that can come back. */
    record RefundRequest(
            @Nullable @Positive Long amountPaise,
            @Nullable @Size(max = 2000) String note) {}

    /**
     * A request and where it stands: {@code requested} (with us), {@code declined} ({@code decision} says why),
     * {@code refunding} (on its way), {@code refunded} or {@code failed} (the credit is back in the wallet).
     */
    record RefundView(
            UUID id,
            String status,
            @Nullable Long requestedPaise,
            @Nullable Long approvedPaise,
            @Nullable Long ticketNumber,
            @Nullable String decision,
            Instant requestedAt,
            @Nullable Instant finishedAt) {}

    /** What can come back now, and the requests, newest first. */
    record RefundList(long refundablePaise, List<RefundView> requests) {}

    static RefundView view(CreditRefunds.Refund refund) {
        return new RefundView(
                refund.id(),
                refund.status(),
                refund.requested() == null ? null : refund.requested().value(),
                refund.approved() == null ? null : refund.approved().value(),
                refund.ticketNumber(),
                refund.decision(),
                refund.requestedAt(),
                refund.finishedAt());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @OpenToHeldAccounts
    RefundView request(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody RefundRequest request) {
        return view(refunds.request(
                user.id(), request.amountPaise() == null ? null : Paise.of(request.amountPaise()), request.note()));
    }

    @GetMapping
    @OpenToHeldAccounts
    RefundList list(@AuthenticationPrincipal CurrentUser user) {
        return new RefundList(
                refunds.refundable(user.id()).value(),
                refunds.ofUser(user.id()).stream()
                        .map(RefundRequestController::view)
                        .toList());
    }
}
