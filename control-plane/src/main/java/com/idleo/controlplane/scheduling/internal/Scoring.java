package com.idleo.controlplane.scheduling.internal;

import java.util.Comparator;

/**
 * The batch score (technical design lines 340-350): S = w_p(1 − P/P_max) + w_r·R + w_l(1 − L/L_max) +
 * w_b·B + w_c·C, with the batch weights. Reliability, bandwidth and cache have no data yet (no host
 * score, no benchmark, no inventory), so they use the defaults below for every host: starting guesses to
 * tune with pilot data (ADR 0015). Ties are broken by host id, so ranking is deterministic.
 */
final class Scoring {

    static final double PRICE_WEIGHT = 0.3;
    static final double RELIABILITY_WEIGHT = 0.35;
    static final double LATENCY_WEIGHT = 0.0;
    static final double BANDWIDTH_WEIGHT = 0.15;
    static final double CACHE_WEIGHT = 0.2;

    /** No host score yet: every host counts as average. */
    static final double DEFAULT_RELIABILITY = 0.5;
    /** No bandwidth benchmark yet: every host counts as average. */
    static final double DEFAULT_BANDWIDTH = 0.5;
    /** No cache inventory yet: nothing counts as cached. */
    static final double DEFAULT_CACHE = 0.0;

    private Scoring() {}

    record Scored(Candidate candidate, double score) {}

    record Candidate(java.util.UUID hostId, int pricePaisePerMin) {}

    /** {@code maxPricePaisePerMin} is P_max: the renter's own maximum. */
    static double score(int pricePaisePerMin, int maxPricePaisePerMin) {
        double priceTerm = 1.0 - (double) pricePaisePerMin / maxPricePaisePerMin;
        return PRICE_WEIGHT * priceTerm
                + RELIABILITY_WEIGHT * DEFAULT_RELIABILITY
                + LATENCY_WEIGHT * 0.0
                + BANDWIDTH_WEIGHT * DEFAULT_BANDWIDTH
                + CACHE_WEIGHT * DEFAULT_CACHE;
    }

    /** Highest score first; equal scores by host id. */
    static final Comparator<Scored> BEST_FIRST = Comparator.comparingDouble(Scored::score)
            .reversed()
            .thenComparing(scored -> scored.candidate().hostId());
}
