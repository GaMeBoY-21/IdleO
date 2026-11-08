package com.idleo.controlplane;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flags for a test (ADR 0056), set as an admin's change sets them: the row, then the hint every instance reads them
 * again on, and the wait until this instance's {@link Flags} answers so. The console's own path is {@code FlagsTests}'s;
 * this is for a test of what a pause does where it's enforced. Every test that pauses one runs them all again after.
 */
public final class TestFlags {

    /** Marks a test of where a flag is enforced: {@code FlagEnforcementCoverageTests} fails on a flag with none. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Enforces {
        Flag value();
    }

    private static final Duration WAIT = Duration.ofSeconds(5);

    private TestFlags() {}

    public static void pause(ApplicationContext context, Flag flag) {
        pause(context, flag, null);
    }

    /** Pauses {@code flag}, expected back at {@code until} when given. */
    public static void pause(ApplicationContext context, Flag flag, @Nullable Instant until) {
        context.getBean(JdbcTemplate.class)
                .update(
                        "update flags.flags set paused = true, until = ?, resume_automatically = false,"
                                + " reason = 'a test pauses it', changed_at = now() where key = ?",
                        until == null ? null : Timestamp.from(until),
                        flag.key());
        hint(context);
        Awaitility.await().atMost(WAIT).until(() -> context.getBean(Flags.class).paused(flag));
    }

    /** Runs every flag again. */
    public static void runAll(ApplicationContext context) {
        context.getBean(JdbcTemplate.class)
                .update(
                        "update flags.flags set paused = false, until = null, resume_automatically = false where paused");
        hint(context);
        var flags = context.getBean(Flags.class);
        Awaitility.await()
                .atMost(WAIT)
                .until(() -> java.util.Arrays.stream(Flag.values()).noneMatch(flags::paused));
    }

    private static void hint(ApplicationContext context) {
        context.getBean(StringRedisTemplate.class).convertAndSend("flags:changed", "changed");
    }
}
