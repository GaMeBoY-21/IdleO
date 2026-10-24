package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.BrowserSignInOnly;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.FreshSignIn;
import com.idleo.controlplane.identity.OpenToHeldAccounts;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hosting, Payout details (ADR 0040): the person's PAN (by its last four characters) and where their payouts go.
 *
 * <ul>
 *   <li>{@code GET /v1/payout-details}: the PAN and its state, the UPI ID in use (with any 48-hour hold after a
 *       change), one waiting for an admin, and the latest check.
 *   <li>{@code PUT /v1/payout-details/pan}: the PAN and the name exactly as on it; checked at once (422 {@code
 *       validation-failed} for its shape, 409 {@code pan-already-verified} once verified).
 *   <li>{@code POST /v1/payout-details/upi-checks} (Idempotency-Key): RazorpayX checks a UPI ID and the answer comes
 *       back with the name the bank has for it; 409 {@code pan-not-verified}, 429 {@code too-many-upi-checks}, 503
 *       {@code payouts-unavailable}.
 *   <li>{@code POST /v1/payout-details/upi-checks/{id}/confirm}: that UPI ID becomes where payouts go (or goes to an
 *       admin when the name match is low), only after a sign-in in the last 10 minutes (403 {@code
 *       fresh-sign-in-required}); 409 {@code upi-check-not-usable}.
 * </ul>
 *
 * Changing these needs a browser sign-in, never an API key.
 */
@RestController
class PayoutDetailsController {

    static final String IDEMPOTENCY_TABLE = "payouts.idempotency_keys";
    static final Duration FRESH = Duration.ofMinutes(10);

    private final Pans pans;
    private final PayoutMethods methods;
    private final PayoutsProperties properties;
    private final RazorpayXClient razorpayx;
    private final IdempotentRequests idempotency;
    private final FreshSignIn fresh;

    PayoutDetailsController(
            Pans pans,
            PayoutMethods methods,
            PayoutsProperties properties,
            RazorpayXClient razorpayx,
            IdempotentRequests idempotency,
            FreshSignIn fresh) {
        this.pans = pans;
        this.methods = methods;
        this.properties = properties;
        this.razorpayx = razorpayx;
        this.idempotency = idempotency;
        this.fresh = fresh;
    }

    /**
     * @param last4 the PAN's last four characters; the rest is never shown
     * @param holder individual or business
     * @param state review (an admin looks), verified, or rejected
     * @param reason why it was rejected or is in review, in the person's words
     */
    record PanView(
            String last4,
            String name,
            String holder,
            String state,
            @Nullable String reason,
            Instant submittedAt,
            @Nullable Instant decidedAt) {

        static PanView of(Pans.Pan pan) {
            return new PanView(
                    pan.last4(),
                    pan.name(),
                    pan.holder(),
                    pan.state(),
                    pan.reason(),
                    pan.submittedAt(),
                    pan.decidedAt());
        }
    }

    /**
     * A UPI ID as RazorpayX checked it.
     *
     * @param state checking, checked (waiting to be confirmed), failed, review (an admin looks), active, suspect (a
     *     payout to it came back: check it again), rejected or replaced
     * @param registeredName the name the bank has for it, for the person to recognise
     * @param nameMatchScore how well that matches the PAN's name, 0 to 100
     * @param failure why a check failed, in the person's words
     * @param holdUntil no payout goes to it before this, after a change of UPI ID
     */
    record UpiView(
            UUID id,
            @Nullable String vpa,
            String state,
            @Nullable String registeredName,
            @Nullable Integer nameMatchScore,
            @Nullable String failure,
            Instant checkedAt,
            @Nullable Instant confirmedAt,
            @Nullable Instant holdUntil,
            @Nullable String reason) {

        static @Nullable UpiView of(PayoutMethods.@Nullable Method method) {
            return method == null
                    ? null
                    : new UpiView(
                            method.id(),
                            method.vpa(),
                            method.state(),
                            method.registeredName(),
                            method.nameMatchScore(),
                            method.failure(),
                            method.checkedAt(),
                            method.confirmedAt(),
                            method.holdUntil(),
                            method.reason());
        }
    }

    /**
     * @param inUse where payouts go (active, or suspect after a payout came back)
     * @param inReview a confirmed UPI ID waiting for an admin
     * @param latestCheck the newest check, when it's newer than the one in use
     * @param payoutsAvailable whether this server can check UPI IDs and pay (RazorpayX is set up)
     * @param nameMatchThreshold the score a check needs to be used without an admin
     */
    record PayoutDetails(
            @Nullable PanView pan,
            @Nullable UpiView inUse,
            @Nullable UpiView inReview,
            @Nullable UpiView latestCheck,
            boolean payoutsAvailable,
            int nameMatchThreshold,
            int checksPerDay,
            long changeHoldHours) {}

    record SubmitPan(
            @NotBlank @Size(max = 20) String pan,
            @NotBlank @Size(max = 85) String name) {}

    record CheckUpi(@NotBlank @Size(max = 100) String vpa) {}

    @OpenToHeldAccounts
    @GetMapping("/v1/payout-details")
    PayoutDetails get(@AuthenticationPrincipal CurrentUser user) {
        var inUse = methods.inUse(user.id()).orElse(null);
        var latest = methods.latest(user.id()).orElse(null);
        var newer = latest != null && (inUse == null || latest.checkedAt().isAfter(inUse.checkedAt())) ? latest : null;
        return new PayoutDetails(
                pans.find(user.id()).map(PanView::of).orElse(null),
                UpiView.of(inUse),
                UpiView.of(methods.inReview(user.id()).orElse(null)),
                UpiView.of(newer),
                razorpayx.enabled(),
                properties.nameMatchThreshold(),
                properties.checksPerDay(),
                properties.changeHold().toHours());
    }

    @OpenToHeldAccounts
    @BrowserSignInOnly
    @PutMapping("/v1/payout-details/pan")
    PanView submitPan(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody SubmitPan request) {
        try {
            return PanView.of(pans.submit(user.id(), request.pan(), request.name()));
        } catch (Pans.Malformed malformed) {
            throw ApiProblem.invalid(malformed.field, malformed.getMessage());
        } catch (Pans.AlreadyVerified verified) {
            throw ApiProblem.of(Problem.PAN_ALREADY_VERIFIED, "Your PAN is verified already.");
        }
    }

    @OpenToHeldAccounts
    @BrowserSignInOnly
    @PostMapping("/v1/payout-details/upi-checks")
    @ApiResponse(
            responseCode = "201",
            description = "The check, with what RazorpayX said; a repeat with the same key returns the first response",
            content = @Content(schema = @Schema(implementation = UpiView.class)))
    ResponseEntity<String> check(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody CheckUpi request) {
        return idempotency.execute(IDEMPOTENCY_TABLE, user.id(), idempotencyKey, request, () -> {
            var method = refusing(() -> methods.check(user.id(), request.vpa()));
            return ResponseEntity.created(URI.create("/v1/payout-details")).body(UpiView.of(method));
        });
    }

    @OpenToHeldAccounts
    @BrowserSignInOnly
    @PostMapping("/v1/payout-details/upi-checks/{id}/confirm")
    UpiView confirm(@AuthenticationPrincipal CurrentUser user, Authentication authentication, @PathVariable UUID id) {
        if (!fresh.within(authentication, FRESH)) {
            throw ApiProblem.of(
                    Problem.FRESH_SIGN_IN_REQUIRED,
                    "Sign in again: changing where payouts go needs a sign-in in the last 10 minutes.");
        }
        return UpiView.of(refusing(() -> methods.confirm(user.id(), id)));
    }

    static PayoutMethods.Method refusing(java.util.function.Supplier<PayoutMethods.Method> action) {
        try {
            return action.get();
        } catch (PayoutMethods.Refused refused) {
            throw switch (refused.refusal) {
                case UNAVAILABLE -> ApiProblem.of(Problem.PAYOUTS_UNAVAILABLE, refused.getMessage());
                case PAN_NOT_VERIFIED -> ApiProblem.of(Problem.PAN_NOT_VERIFIED, refused.getMessage());
                case MALFORMED -> ApiProblem.invalid("vpa", refused.getMessage());
                case TOO_MANY ->
                    ApiProblem.of(Problem.TOO_MANY_UPI_CHECKS, refused.getMessage())
                            .retryAfter(java.util.Objects.requireNonNull(refused.retryAfter));
                case NOT_USABLE -> ApiProblem.of(Problem.UPI_CHECK_NOT_USABLE, refused.getMessage());
            };
        }
    }
}
