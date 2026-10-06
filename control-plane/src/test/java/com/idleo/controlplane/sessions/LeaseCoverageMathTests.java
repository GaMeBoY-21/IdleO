package com.idleo.controlplane.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Which minutes a lease would let an attempt start, so the credit can cover exactly those (ADR 0017). */
class LeaseCoverageMathTests {

    private static final Instant RUNNING = Instant.parse("2026-10-06T10:00:00Z");
    private static final Duration TWO_MINUTES = Duration.ofMinutes(2);

    private static long at(long secondsAfterRunning, Duration ttl) {
        return MeteredSessions.minutesALeaseStarts(RUNNING, RUNNING.plusSeconds(secondsAfterRunning), ttl);
    }

    @Test
    void aLeaseSentAtAMinutesStartCoversThatMinuteAndOneMoreNewOne() {
        assertThat(at(0, TWO_MINUTES)).isEqualTo(1);
        assertThat(at(60, TWO_MINUTES)).isEqualTo(1);
    }

    @Test
    void aLeaseSentMidMinuteReachesIntoTwoMoreMinutes() {
        assertThat(at(30, TWO_MINUTES)).isEqualTo(2);
        assertThat(at(119, TWO_MINUTES)).isEqualTo(2);
    }

    @Test
    void aShortLeaseInsideTheCurrentMinuteStartsNothingNew() {
        assertThat(at(0, Duration.ofSeconds(10))).isZero();
        assertThat(at(49, Duration.ofSeconds(10))).isZero();
        assertThat(at(51, Duration.ofSeconds(10))).isEqualTo(1);
    }

    @Test
    void beforeTheFirstRunningEveryMinuteTheLeaseSpansCounts() {
        assertThat(MeteredSessions.minutesALeaseStarts(null, RUNNING, TWO_MINUTES))
                .isEqualTo(2);
        assertThat(MeteredSessions.minutesALeaseStarts(null, RUNNING, Duration.ofSeconds(10)))
                .isEqualTo(1);
    }
}
