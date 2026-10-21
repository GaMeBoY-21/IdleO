package com.idleo.controlplane;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.stereotype.Component;

/**
 * What readiness checks, as meters (ADR 0036): {@code health.ready} is 1 while a component answers and 0 while it
 * doesn't, by {@code component}: the database ({@code db}) and the agents' CA ({@code ca}). The database alert reads
 * it; asked once a minute, when the meters are sent. Object storage isn't part of readiness: sessions' own
 * {@code storage.reachable} says whether it answers.
 */
@Component
class ReadinessMetrics {

    static final List<String> COMPONENTS = List.of("db", "ca");

    ReadinessMetrics(MeterRegistry registry, HealthContributorRegistry health) {
        for (var component : COMPONENTS) {
            Gauge.builder("health.ready", () -> ready(health, component))
                    .tag("component", component)
                    .description("1 while this readiness component answers, 0 while it doesn't")
                    .register(registry);
        }
    }

    private static double ready(HealthContributorRegistry health, String component) {
        if (!(health.getContributor(component) instanceof HealthIndicator indicator)) {
            return 0;
        }
        return Status.UP.equals(indicator.health(false).getStatus()) ? 1 : 0;
    }
}
