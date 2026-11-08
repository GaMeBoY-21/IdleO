package com.idleo.controlplane;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Whether a {@link Flag} is paused now (ADR 0056), asked by the module where the thing it stops happens. The flags
 * module implements it from a cache of {@code flags.flags}, invalidated through Valkey and read again every 30 s; when
 * the flags can't be read at all, each flag is its {@link Flag#pausedIfUnknown safe value}. Never throws, and never
 * waits on the database.
 */
public interface Flags {

    /** India's time, which a refusal's expected end is told in. */
    ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /**
     * A paused flag.
     *
     * @param until when it's expected to run again, if the admin said; it runs again by itself then only if they asked
     */
    record Pause(Flag flag, @Nullable Instant until) {

        /** "Bookings are paused: …", with the expected end when there is one, for a refusal and the app's banner. */
        public String message() {
            var end = until == null
                    ? "we'll be back as soon as we can."
                    : "we expect to be back by "
                            + DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
                                    .format(until.atZone(INDIA))
                            + " India time.";
            return flag.pausedText() + ": " + end;
        }
    }

    /** The pause of {@code flag}, or empty when it's running. */
    Optional<Pause> pause(Flag flag);

    /** Whether {@code flag} is paused now. */
    default boolean paused(Flag flag) {
        return pause(flag).isPresent();
    }

    /**
     * Refuses the request when {@code flag} is paused: a {@code paused} problem naming the flag, with Retry-After when
     * the expected end is known. Never used for {@link Flag#MAINTENANCE}, whose refusal is maintenance mode's own.
     */
    void require(Flag flag);

    /** What a request meets while {@code pause} holds. */
    static ApiProblem refusal(Pause pause, Instant now) {
        var problem = pause.flag() == Flag.MAINTENANCE
                ? ApiProblem.of(
                        Problem.MAINTENANCE,
                        pause.message(),
                        "flag",
                        pause.flag().key())
                : ApiProblem.of(
                        Problem.PAUSED, pause.message(), "flag", pause.flag().key());
        var until = pause.until();
        if (until == null || !until.isAfter(now)) {
            // The client tries again in a minute, which is also how soon a change reaches every instance.
            return problem.retryAfter(Duration.ofMinutes(1));
        }
        return problem.retryAfter(Duration.between(now, until));
    }
}
