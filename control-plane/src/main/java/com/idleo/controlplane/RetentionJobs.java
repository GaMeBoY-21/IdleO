package com.idleo.controlplane;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link RetentionRule} (ADR 0037): shortly after start and then every {@code retention.every} (a day),
 * each under a lock of its own ({@link OneAtATime}), so one instance at a time applies a rule. Meters, by {@code rule}:
 * {@code retention.deleted}, what each run deleted; {@code retention.failures}; and {@code retention.age}, the
 * seconds since this instance last applied it (since start, before its first run), which the retention-stopped alert
 * reads across instances.
 */
@Component
@Profile("!ops")
class RetentionJobs {

    private static final Logger log = LoggerFactory.getLogger(RetentionJobs.class);

    private final List<RetentionRule> rules;
    private final OneAtATime oneAtATime;
    private final Clock clock;
    private final MeterRegistry registry;
    private final Map<String, Instant> lastSuccess = new ConcurrentHashMap<>();

    RetentionJobs(List<RetentionRule> rules, OneAtATime oneAtATime, Clock clock, MeterRegistry registry) {
        var names = rules.stream().collect(Collectors.groupingBy(RetentionRule::name, Collectors.counting()));
        names.forEach((name, count) -> {
            if (count > 1) {
                throw new IllegalStateException("two retention rules are named " + name);
            }
        });
        this.rules = List.copyOf(rules);
        this.oneAtATime = oneAtATime;
        this.clock = clock;
        this.registry = registry;
        var started = Instant.now(clock);
        for (var rule : rules) {
            lastSuccess.put(rule.name(), started);
            Gauge.builder("retention.age", this, jobs -> jobs.ageSeconds(rule.name()))
                    .tag("rule", rule.name())
                    .baseUnit("seconds")
                    .description("Seconds since this instance last applied the retention rule")
                    .register(registry);
        }
    }

    /** The rules, by name. */
    Map<String, RetentionRule> rules() {
        return rules.stream().collect(Collectors.toMap(RetentionRule::name, Function.identity()));
    }

    @Scheduled(initialDelayString = "${retention.first-run-after}", fixedDelayString = "${retention.every}")
    void runAll() {
        for (var rule : rules) {
            run(rule);
        }
    }

    /** Applies one rule if no other instance is applying it; what it deleted, or -1 when it didn't run. */
    long run(RetentionRule rule) {
        try {
            var deleted = oneAtATime.run("retention:" + rule.name(), () -> rule.apply(Instant.now(clock)));
            if (deleted.isEmpty()) {
                return -1;
            }
            registry.counter("retention.deleted", "rule", rule.name()).increment(deleted.get());
            lastSuccess.put(rule.name(), Instant.now(clock));
            if (deleted.get() > 0) {
                log.info("Retention rule {} deleted {}", rule.name(), deleted.get());
            }
            return deleted.get();
        } catch (RuntimeException e) {
            registry.counter("retention.failures", "rule", rule.name()).increment();
            log.warn("Retention rule {} failed; it runs again at the next round", rule.name(), e);
            return -1;
        }
    }

    private double ageSeconds(String rule) {
        var last = lastSuccess.get(rule);
        return last == null ? 0 : Duration.between(last, Instant.now(clock)).toSeconds();
    }
}
