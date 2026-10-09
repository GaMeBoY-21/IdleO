package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.sessions.PriceCeilings;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.stereotype.Component;

/**
 * The server-set ceiling (ADR 0021): the scheduler's own free hosts, at any price, with enough GPU memory; the highest
 * of their prices. The booking that follows is matched by the same filters, so a ceiling chosen here always lets at
 * least one host take it.
 */
@Component
class FittingPrices implements PriceCeilings {

    private final CandidateFinder candidates;

    FittingPrices(CandidateFinder candidates) {
        this.candidates = candidates;
    }

    @Override
    public OptionalInt highestFitting(int vramNeededMb, int diskNeededMb) {
        return candidates.free(Integer.MAX_VALUE, vramNeededMb, diskNeededMb, List.of()).stream()
                .mapToInt(candidate -> candidate.pricePaisePerMin())
                .max();
    }
}
