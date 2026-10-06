package com.idleo.controlplane;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** The system clock, plus an offset tests can move forward, for expiry tests. */
public final class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public void advance(Duration amount) {
        offset.updateAndGet(current -> current.plus(amount));
    }

    public void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("the test clock is always UTC");
    }
}
