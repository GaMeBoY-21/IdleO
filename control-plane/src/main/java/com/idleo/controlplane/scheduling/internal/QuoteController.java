package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.sessions.CreateSessionRequest;
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
 * filters, so the quote and the booking that follows never disagree.
 */
@RestController
@RequestMapping("/v1/session-quotes")
class QuoteController {

    private final SessionRequests requests;
    private final CreditReservations reservations;
    private final CandidateFinder candidates;

    QuoteController(SessionRequests requests, CreditReservations reservations, CandidateFinder candidates) {
        this.requests = requests;
        this.reservations = reservations;
        this.candidates = candidates;
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
     */
    record QuoteResponse(
            long holdPaise,
            long availablePaise,
            boolean enoughCredit,
            int hostsFitNow,
            @Nullable PriceRange pricePaisePerMin) {}

    @PostMapping
    QuoteResponse create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateSessionRequest request) {
        var checked = requests.check(user.id(), request);
        var hold = CreditReservations.holdFor(request.maxPricePaisePerMin(), Paise.of(request.maxSpendPaise()));
        var available = reservations.available(user.id());
        var fit = candidates.free(request.maxPricePaisePerMin(), checked.vramNeededMb(), List.of());
        var prices = fit.stream()
                .mapToInt(candidate -> candidate.pricePaisePerMin())
                .sorted()
                .toArray();
        return new QuoteResponse(
                hold.value(),
                available.value(),
                !hold.isGreaterThan(available),
                fit.size(),
                prices.length == 0 ? null : new PriceRange(prices[0], prices[prices.length - 1]));
    }
}
