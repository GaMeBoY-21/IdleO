package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.sessions.CreateSessionRequest;
import com.idleo.controlplane.sessions.PriceCeilings;
import com.idleo.controlplane.sessions.SessionRequests;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A quote for a booking before it's made (ADR 0019): the same body as {@code POST /v1/sessions}, run through the same
 * check ({@link SessionRequests}), so a request that quotes cleanly books cleanly. It reserves nothing. The hold is
 * the one booking makes ({@link CreditReservations#holdFor}), and the hosts that fit are found by the scheduler's own
 * filters, so the quote and the booking that follows never disagree. Two differences from booking (ADR 0021): the
 * input may be left out, so the price shows while an upload is still going; and a quote without a maximum price
 * uses the server's ceiling, as booking would, or is refused with no-gpu-free when no GPU fits.
 */
@RestController
@RequestMapping("/v1/session-quotes")
class QuoteController {

    private final SessionRequests requests;
    private final CreditReservations reservations;
    private final CandidateFinder candidates;
    private final PriceCeilings ceilings;

    QuoteController(
            SessionRequests requests,
            CreditReservations reservations,
            CandidateFinder candidates,
            PriceCeilings ceilings) {
        this.requests = requests;
        this.reservations = reservations;
        this.candidates = candidates;
        this.ceilings = ceilings;
    }

    /** The lowest and highest price, in paise a minute. */
    record PriceRange(int min, int max) {}

    /**
     * What booking this request would mean.
     *
     * @param holdPaise what booking holds: 30 minutes at the maximum price, up to the maximum spend
     * @param availablePaise the renter's credit not already held
     * @param enoughCredit the hold fits in the available credit; booking is refused with insufficient-credit if not
     * @param hostsFitNow hosts that could take it right now
     * @param pricePaisePerMin the prices of those hosts; null when none fits
     * @param ceilingPaisePerMin the maximum price the booking would have: the renter's, or, when left out, the
     *     highest price among the GPUs that fit now
     */
    record QuoteResponse(
            long holdPaise,
            long availablePaise,
            boolean enoughCredit,
            int hostsFitNow,
            @Nullable PriceRange pricePaisePerMin,
            int ceilingPaisePerMin) {}

    @PostMapping
    QuoteResponse create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateSessionRequest request) {
        var checked = requests.check(user.id(), request, true);
        int ceiling = ceilings.ceiling(request.maxPricePaisePerMin(), checked.vramNeededMb());
        var hold = CreditReservations.holdFor(ceiling, Paise.of(request.maxSpendPaise()));
        var available = reservations.available(user.id());
        var fit = candidates.free(ceiling, checked.vramNeededMb(), List.of());
        var prices = fit.stream()
                .mapToInt(candidate -> candidate.pricePaisePerMin())
                .sorted()
                .toArray();
        return new QuoteResponse(
                hold.value(),
                available.value(),
                !hold.isGreaterThan(available),
                fit.size(),
                prices.length == 0 ? null : new PriceRange(prices[0], prices[prices.length - 1]),
                ceiling);
    }
}
