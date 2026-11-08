package com.idleo.controlplane.flags.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.Flag;
import com.idleo.controlplane.MutableClock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** One instance's copy of the flags (ADR 0056): what it answers when the database can't be read, and when time is up. */
class FlagCacheTests {

    private static final Duration TRUST_FOR = Duration.ofMinutes(2);

    private final MutableClock clock = new MutableClock();
    private final AtomicReference<Supplier<Map<Flag, FlagCache.Row>>> database = new AtomicReference<>(() -> {
        throw new IllegalStateException("the database can't be reached");
    });
    private final java.util.concurrent.atomic.AtomicLong ticker = new java.util.concurrent.atomic.AtomicLong();
    private final FlagCache cache = new FlagCache(() -> database.get().get(), clock, ticker::get, TRUST_FOR);

    private void elapse(Duration amount) {
        ticker.addAndGet(amount.toNanos());
    }

    private static Map<Flag, FlagCache.Row> allRunning() {
        var rows = new EnumMap<Flag, FlagCache.Row>(Flag.class);
        for (var flag : Flag.values()) {
            rows.put(flag, new FlagCache.Row(flag, false, null, false));
        }
        return rows;
    }

    @Test
    void beforeAnyReadWorksEachFlagIsItsSafeValue() {
        assertThat(cache.reload()).isFalse();
        for (var flag : Flag.values()) {
            assertThat(cache.pause(flag).isPresent()).as(flag.key()).isEqualTo(flag.pausedIfUnknown());
        }
        assertThat(cache.pause(Flag.PAYOUTS_SEND))
                .as("money waits when we can't tell")
                .isPresent();
        assertThat(cache.pause(Flag.IMAGES_CUSTOM))
                .as("nothing risky goes unseen")
                .isPresent();
        assertThat(cache.pause(Flag.BOOKINGS_NEW))
                .as("a blip doesn't close the business")
                .isEmpty();
        assertThat(cache.pause(Flag.MAINTENANCE)).isEmpty();
    }

    @Test
    void aFailedReadKeepsTheLastGoodCopyForAWhileThenFallsBackToTheSafeValues() {
        database.set(FlagCacheTests::allRunning);
        assertThat(cache.reload()).isTrue();
        database.set(() -> {
            throw new IllegalStateException("the database can't be reached");
        });

        clock.advance(Duration.ofDays(7));
        assertThat(cache.pause(Flag.PAYOUTS_SEND))
                .as("the business clock moving says nothing of the copy's age")
                .isEmpty();
        elapse(TRUST_FOR.minusSeconds(1));
        assertThat(cache.reload()).isFalse();
        assertThat(cache.pause(Flag.PAYOUTS_SEND))
                .as("the last good copy still stands")
                .isEmpty();

        elapse(Duration.ofSeconds(2));
        assertThat(cache.pause(Flag.PAYOUTS_SEND)).as("too old: the safe value").isPresent();
        assertThat(cache.pause(Flag.BOOKINGS_NEW)).isEmpty();

        database.set(FlagCacheTests::allRunning);
        assertThat(cache.reload()).isTrue();
        assertThat(cache.pause(Flag.PAYOUTS_SEND)).as("read again: the truth").isEmpty();
    }

    @Test
    void aPauseThatRunsAgainByItselfEndsAtItsTimeWithoutWaitingForTheDatabase() {
        var until = clock.instant().plus(Duration.ofHours(2));
        var rows = allRunning();
        rows.put(Flag.TOPUPS, new FlagCache.Row(Flag.TOPUPS, true, until, true));
        rows.put(Flag.SIGNUPS, new FlagCache.Row(Flag.SIGNUPS, true, until, false));
        database.set(() -> rows);
        cache.reload();

        assertThat(cache.pause(Flag.TOPUPS))
                .hasValueSatisfying(pause -> assertThat(pause.until()).isEqualTo(until));
        clock.advance(Duration.ofMinutes(119));
        cache.reload();
        assertThat(cache.pause(Flag.TOPUPS)).isPresent();
        clock.advance(Duration.ofMinutes(1));
        assertThat(cache.pause(Flag.TOPUPS)).as("its time is up").isEmpty();
        assertThat(cache.pause(Flag.SIGNUPS))
                .as("an expected end that doesn't run it again by itself only says when")
                .isPresent();
    }

    @Test
    void aFlagTheDatabaseDoesntHaveYetIsItsSafeValue() {
        var rows = allRunning();
        rows.remove(Flag.PAYOUTS_SEND);
        database.set(() -> rows);
        cache.reload();
        assertThat(cache.pause(Flag.PAYOUTS_SEND)).isPresent();
    }
}
