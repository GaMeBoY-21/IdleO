package com.idleo.controlplane;

import java.time.Instant;
import java.util.function.ToLongFunction;

/**
 * One retention rule of the personal data map (docs/reference/personal-data-map.md, ADR 0037): something kept for a
 * period and then deleted. A module declares each as a bean; {@link RetentionJobs} runs them all, each under its own
 * lock, counts what each deleted and watches that each keeps running. A rule is idempotent: run twice, or stopped
 * halfway and run again, it deletes what's due once.
 */
public interface RetentionRule {

    /** The rule's name, exactly as the map's "Retention rules" table has it: {@code security-events}. */
    String name();

    /** Deletes what is due at {@code now}; how many rows or objects it deleted. */
    long apply(Instant now);

    /** A rule from its name and what it does. */
    static RetentionRule of(String name, ToLongFunction<Instant> apply) {
        return new RetentionRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public long apply(Instant now) {
                return apply.applyAsLong(now);
            }
        };
    }
}
