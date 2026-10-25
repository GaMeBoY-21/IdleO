package com.idleo.controlplane.scheduling.internal;

import java.util.Comparator;

/**
 * The batch score (technical design lines 359-369): S = w_p(1 − P/P_max) + w_r·R + w_l(1 − L/L_max) +
 * w_b·B + w_c·C, with the batch weights: starting guesses to tune with pilot data (ADR 0015). R is the host
 * score's reliability and B its bandwidth, both from 0 to 1 and kept by the hosts module (0.5 for a host with no
 * data yet); C is 1 when the agent's last heartbeat said the template's image is already on the machine. Ties are
 * broken by host id, so ranking is deterministic.
 */
final class Scoring {

    static final double PRICE_WEIGHT = 0.3;
    static final double RELIABILITY_WEIGHT = 0.35;
    static final double LATENCY_WEIGHT = 0.0;
    static final double BANDWIDTH_WEIGHT = 0.15;
    static final double CACHE_WEIGHT = 0.2;

    private Scoring() {}

    record Scored(Candidate candidate, double score) {}

    record Candidate(java.util.UUID hostId, int pricePaisePerMin, String gpuUuid) {}

    /**
     * {@code maxPricePaisePerMin} is P_max: the renter's own maximum. {@code reliability} and {@code bandwidth} are
     * from 0 to 1; {@code cached} is whether the template's image is already on the host.
     */
    static double score(
            int pricePaisePerMin, int maxPricePaisePerMin, double reliability, double bandwidth, boolean cached) {
        double priceTerm = 1.0 - (double) pricePaisePerMin / maxPricePaisePerMin;
        return PRICE_WEIGHT * priceTerm
                + RELIABILITY_WEIGHT * reliability
                + LATENCY_WEIGHT * 0.0
                + BANDWIDTH_WEIGHT * bandwidth
                + CACHE_WEIGHT * (cached ? 1.0 : 0.0);
    }

    /** The {@code sha256:...} part of a pinned image reference, which is how an agent names what it has. */
    static String digest(String image) {
        int at = image.indexOf('@');
        return at < 0 ? image : image.substring(at + 1);
    }

    /** Highest score first; equal scores by host id. */
    static final Comparator<Scored> BEST_FIRST = Comparator.comparingDouble(Scored::score)
            .reversed()
            .thenComparing(scored -> scored.candidate().hostId());
}
