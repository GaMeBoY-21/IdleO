package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.RequiresAcceptedTerms;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A renter's top-ups (ADR 0039): order one, confirm Checkout's payment, and read where each stands. The browser opens
 * Razorpay's Checkout with the order; the credit arrives only once the server has proof the payment was captured, from
 * this confirmation or from Razorpay's webhook, whichever is first. Polling {@code GET …/{id}} shows when it has.
 */
@RestController
@RequestMapping("/v1/wallet/topups")
class TopUpController {

    private final TopUps topUps;
    private final IdempotentRequests idempotent;
    private final RazorpayProperties razorpay;

    TopUpController(TopUps topUps, IdempotentRequests idempotent, RazorpayProperties razorpay) {
        this.topUps = topUps;
        this.idempotent = idempotent;
        this.razorpay = razorpay;
    }

    record TopUpRequest(@Positive long amountPaise) {}

    record ConfirmRequest(
            @NotBlank @Size(max = 64) String razorpayPaymentId,
            @Nullable @Size(max = 64) String razorpayOrderId,
            @NotBlank @Size(max = 128) String razorpaySignature) {}

    /**
     * A top-up and what Checkout needs to take its payment: Razorpay's key id (the public half) and the order. {@code
     * status} is {@code pending}, {@code paid}, {@code failed} (with {@code failureReason}, Razorpay's own words) or
     * {@code abandoned}; {@code method}, and for a card its network and last four digits, once Razorpay says.
     */
    record TopUpResponse(
            UUID id,
            long amountPaise,
            String currency,
            String status,
            String orderId,
            String keyId,
            @Nullable String method,
            @Nullable String cardNetwork,
            @Nullable String cardLast4,
            @Nullable String failureReason,
            Instant createdAt,
            @Nullable Instant paidAt) {}

    record TopUpList(List<TopUpResponse> topUps) {}

    TopUpResponse view(TopUps.TopUp topUp) {
        return new TopUpResponse(
                topUp.id(),
                topUp.amount().value(),
                "INR",
                topUp.status().dbValue(),
                topUp.orderId(),
                razorpay.keyId() == null ? "" : razorpay.keyId(),
                topUp.method(),
                topUp.cardNetwork(),
                topUp.cardLast4(),
                topUp.failureReason(),
                topUp.createdAt(),
                topUp.paidAt());
    }

    @PostMapping
    @RequiresAcceptedTerms
    @ApiResponse(
            responseCode = "201",
            description =
                    "The new top-up, with Razorpay's order for Checkout; a repeat with the same key returns the first"
                            + " response",
            headers = @Header(name = "Location", description = "The top-up's URL", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = TopUpResponse.class)))
    ResponseEntity<String> create(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String key,
            @Valid @RequestBody TopUpRequest request) {
        return idempotent.execute("billing.idempotency_keys", user.id(), key, request, () -> {
            var topUp = topUps.order(user.id(), Paise.of(request.amountPaise()));
            return ResponseEntity.created(URI.create("/v1/wallet/topups/" + topUp.id()))
                    .body(view(topUp));
        });
    }

    /**
     * Checkout's handler hands over the payment. The answer is the top-up as it stands after the check: {@code paid}
     * once credited, or still {@code pending} while Razorpay hasn't captured it (keep polling).
     */
    @PostMapping("/{id}/confirm")
    TopUpResponse confirm(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ConfirmRequest request) {
        var topUp = own(user, id);
        if (request.razorpayOrderId() != null && !request.razorpayOrderId().equals(topUp.orderId())) {
            throw ApiProblem.of(Problem.PAYMENT_NOT_VERIFIED, "The payment is for another order than this top-up's.");
        }
        topUps.confirm(topUp, request.razorpayPaymentId(), request.razorpaySignature());
        return view(own(user, id));
    }

    @GetMapping("/{id}")
    TopUpResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return view(own(user, id));
    }

    /** The renter's latest top-ups, newest first. */
    @GetMapping
    TopUpList list(@AuthenticationPrincipal CurrentUser user) {
        return new TopUpList(
                topUps.ofUser(user.id(), 50).stream().map(this::view).toList());
    }

    private TopUps.TopUp own(CurrentUser user, UUID id) {
        return topUps.find(id)
                .filter(topUp -> topUp.userId().equals(user.id()))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no top-up " + id + "."));
    }
}
