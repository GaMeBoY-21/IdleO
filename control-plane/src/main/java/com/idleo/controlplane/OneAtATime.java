package com.idleo.controlplane;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Work that must run on one control-plane instance at a time (ADR 0047): under a PostgreSQL session-level advisory
 * lock named for it, taken with {@code pg_try_advisory_lock}, so an instance that finds it held skips this turn
 * instead of waiting. The lock is held on one connection for as long as the work runs, while the work uses connections
 * of its own, so it may span many transactions and calls to other services. If the instance dies, its connection
 * closes and the lock goes with it.
 *
 * <p>A job that is one transaction takes {@code pg_try_advisory_xact_lock} inside it instead (WalletReconciliation,
 * FreeCreditExpiry): the same lock, released at commit. Both are the one mechanism; there is no other.
 *
 * <p>{@code jobs.one_at_a_time}, by {@code job} and {@code outcome} (ran or skipped), counts each turn.
 */
@Component
public class OneAtATime {

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    OneAtATime(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    /** Runs {@code work} if no instance is running {@code job}: its result, or empty if one was. */
    public <T> Optional<T> run(String job, Supplier<T> work) {
        return run(job, null, work);
    }

    /** Runs {@code work} if no instance is running {@code job}: whether it ran. */
    public boolean run(String job, Runnable work) {
        return run(job, null, () -> {
                    work.run();
                    return Boolean.TRUE;
                })
                .isPresent();
    }

    /**
     * Runs {@code work} if no instance is running {@code job} for {@code key} (one erasure request, say), so each key
     * has a lock of its own and the meter counts the job: its result, or empty if one was.
     */
    public <T> Optional<T> run(String job, @Nullable String key, Supplier<T> work) {
        var name = key == null ? job : job + ":" + key;
        var held = jdbc.execute((ConnectionCallback<Held<T>>) connection -> {
            try (var lock = connection.prepareStatement("select pg_try_advisory_lock(hashtextextended(?, 0))")) {
                lock.setString(1, name);
                try (var result = lock.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) {
                        return null;
                    }
                }
            }
            try {
                return new Held<>(work.get());
            } finally {
                try (var unlock = connection.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))")) {
                    unlock.setString(1, name);
                    unlock.execute();
                }
            }
        });
        meters.counter("jobs.one_at_a_time", "job", job, "outcome", held == null ? "skipped" : "ran")
                .increment();
        return held == null ? Optional.empty() : Optional.ofNullable(held.value());
    }

    /** A result that may itself be null, told apart from "didn't run". */
    private record Held<T>(T value) {}
}
