package com.idleo.controlplane.identity.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What the insider-risk alerts read (ADR 0051, ops/alerts/security.yaml): counters, carrying no one's id; who it was is
 * in the audit log and the access log, which the runbook line points at.
 */
@Component
class AdminInsiderSignals {

    private final Counter newPlace;
    private final Counter soloApproval;

    AdminInsiderSignals(MeterRegistry registry) {
        this.newPlace = Counter.builder("admin.insider.signal")
                .tag("kind", "new_place")
                .description("Signals the insider-risk alerts read")
                .register(registry);
        this.soloApproval = Counter.builder("admin.insider.signal")
                .tag("kind", "solo_approval")
                .description("Signals the insider-risk alerts read")
                .register(registry);
    }

    /** An admin signed in from a browser or an address they haven't used. */
    void newPlace() {
        newPlace.increment();
    }

    /** A gated action has no one else to approve it. */
    void soloApproval() {
        soloApproval.increment();
    }
}
