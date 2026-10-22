package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What the runners' watch for software no renter may run has found (ADR 0038, ADR 0036): sessions stopped, and signals that
 * only tell an admin, each by the kind of signal (a fixed handful, so the tag is bounded). Counted after the transaction that
 * recorded them commits. The alert AbuseStops is more than 3 stops in an hour across the fleet: an attack, or a bad
 * blocklist entry stopping honest renters.
 */
@Component
class AbuseMetrics {

    private final MeterRegistry registry;

    AbuseMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A session was stopped by its host's runner for this kind of signal. */
    void stop(String kind) {
        AfterCommit.run(
                () -> registry.counter("trust.abuse.stops", "kind", kind).increment());
    }

    /** A signal opened or joined a review item without stopping anything. */
    void flag(String kind) {
        AfterCommit.run(
                () -> registry.counter("trust.abuse.flags", "kind", kind).increment());
    }
}
