package com.idleo.controlplane;

import java.time.Instant;

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
}
