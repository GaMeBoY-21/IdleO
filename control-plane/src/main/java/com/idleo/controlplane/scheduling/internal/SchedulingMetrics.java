package com.idleo.controlplane.scheduling.internal;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Booking meters (ADR 0015). */
@Component
class SchedulingMetrics {

    private final MeterRegistry registry;
    private final Timer matchDuration;
    private final DistributionSummary candidates;

    SchedulingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.matchDuration = Timer.builder("scheduling.match.duration")
                .description("How long one matching attempt took, from candidates to commit")
                .register(registry);
        this.candidates = DistributionSummary.builder("scheduling.match.candidates")
                .description("Candidates considered per matching attempt")
                .register(registry);
    }

    void matched(Duration took, int considered) {
        matchDuration.record(took);
        candidates.record(considered);
        outcome("matched");
    }

    void outcome(String outcome) {
        registry.counter("scheduling.match.outcome", "outcome", outcome).increment();
        if (outcome.equals("no_host_free")) {
            registry.counter("scheduling.no_host_free").increment();
        }
    }

    void offerAnswered(String status) {
        registry.counter("scheduling.offers.answered", "status", status).increment();
    }
}
