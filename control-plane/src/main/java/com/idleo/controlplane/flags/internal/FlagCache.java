package com.idleo.controlplane.flags.internal;

import com.idleo.controlplane.Flag;
import com.idleo.controlplane.Flags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One instance's copy of the flags (ADR 0056), and what it answers when they can't be read. Reading never touches the
 * database: {@link #reload} does, on its timer and when a hint arrives. A failed reload keeps the last good copy for
 * {@code trustFor}; after that, or before any read has worked, every flag is its {@link Flag#pausedIfUnknown safe
 * value}. A pause whose time is up and that runs again by itself is over at that time, here, without waiting for the
 * database to say so.
 */
final class FlagCache {

    private static final Logger log = LoggerFactory.getLogger(FlagCache.class);

    /** One flag as the database holds it. */
    record Row(Flag flag, boolean paused, @Nullable Instant until, boolean resumeAutomatically) {

        boolean pausedAt(Instant now) {
            return paused && !(resumeAutomatically && until != null && !until.isAfter(now));
        }
    }

    /** What was read, and when on this instance's monotonic ticker: a copy's age is a delay, not a business time. */
    private record Copy(Map<Flag, Row> rows, long readAtNanos) {}

    private final Supplier<Map<Flag, Row>> reader;
    private final Clock clock;
    private final LongSupplier ticker;
    private final Duration trustFor;
    private final AtomicReference<@Nullable Copy> copy = new AtomicReference<>();

    FlagCache(Supplier<Map<Flag, Row>> reader, Clock clock, Duration trustFor) {
        this(reader, clock, System::nanoTime, trustFor);
    }

    /** {@code ticker}: nanoseconds, as {@link System#nanoTime}, which a test moves. */
    FlagCache(Supplier<Map<Flag, Row>> reader, Clock clock, LongSupplier ticker, Duration trustFor) {
        this.reader = reader;
        this.clock = clock;
        this.ticker = ticker;
        this.trustFor = trustFor;
    }

    /** Reads every flag; on failure keeps what it had. Whether the read worked. */
    boolean reload() {
        try {
            var rows = new EnumMap<Flag, Row>(Flag.class);
            rows.putAll(reader.get());
            copy.set(new Copy(Map.copyOf(rows), ticker.getAsLong()));
            return true;
        } catch (RuntimeException e) {
            log.warn("Couldn't read the flags; keeping the last good copy for {}: {}", trustFor, e.toString());
            return false;
        }
    }

    /** The pause of {@code flag} now, from the copy, or its safe value when the copy is missing or too old. */
    Optional<Flags.Pause> pause(Flag flag) {
        var now = clock.instant();
        var held = copy.get();
        if (held == null || ticker.getAsLong() - held.readAtNanos() > trustFor.toNanos()) {
            return flag.pausedIfUnknown() ? Optional.of(new Flags.Pause(flag, null)) : Optional.empty();
        }
        var row = held.rows().get(flag);
        if (row == null) {
            // A flag the database doesn't have yet (a release ahead of its migration): its safe value.
            return flag.pausedIfUnknown() ? Optional.of(new Flags.Pause(flag, null)) : Optional.empty();
        }
        return row.pausedAt(now) ? Optional.of(new Flags.Pause(flag, row.until())) : Optional.empty();
    }
}
