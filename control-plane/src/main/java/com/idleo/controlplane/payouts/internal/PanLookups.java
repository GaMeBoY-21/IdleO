package com.idleo.controlplane.payouts.internal;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fills the lookup value of PANs that have none (ADR 0052): those given before it existed, and any the release before
 * writes during a canary (ADR 0046). A PAN is found by an exact match only once it has one.
 */
@Component
class PanLookups {

    private static final int BATCH = 200;

    private final Pans pans;

    PanLookups(Pans pans) {
        this.pans = pans;
    }

    @Scheduled(fixedDelayString = "${payouts.pan.lookup-fill-every:5m}")
    void fill() {
        while (pans.fillLookups(BATCH) == BATCH) {
            Thread.yield();
        }
    }
}
