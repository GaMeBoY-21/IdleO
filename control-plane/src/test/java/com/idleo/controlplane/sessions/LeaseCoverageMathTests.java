package com.idleo.controlplane.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Which minutes a lease would let an attempt start, so the credit can cover exactly those (ADR 0017). */
class LeaseCoverageMathTests {

    private static final Instant RUNNING = Instant.parse("2026-10-06T10:00:00Z");
    private static final Duration TWO_MINUTES = Duration.ofMinutes(2);

    /** A lease sent with metering's count from the same moment. */
    private static long at(long secondsAfterRunning, Duration ttl) {
        var sentAt = RUNNING.plusSeconds(secondsAfterRunning);
        return MeteredSessions.minutesALeaseStarts(RUNNING, sentAt, sentAt, ttl);
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
        assertThat(MeteredSessions.minutesALeaseStarts(null, null, RUNNING, TWO_MINUTES))
                .isEqualTo(2);
        assertThat(MeteredSessions.minutesALeaseStarts(null, null, RUNNING, Duration.ofSeconds(10)))
                .isEqualTo(1);
    }

    @Test
    void aLeaseEndingAMomentIntoAMinuteLetsThatMinuteStart() {
        // Sent half a second into minute 0, it ends half a second into minute 2: minutes 1 and 2 begin under it.
        var sentAt = RUNNING.plusMillis(500);
        assertThat(MeteredSessions.minutesALeaseStarts(RUNNING, sentAt, sentAt, TWO_MINUTES))
                .isEqualTo(2);
        var justBefore = RUNNING.plusSeconds(59).plusMillis(999);
        assertThat(MeteredSessions.minutesALeaseStarts(RUNNING, justBefore, justBefore, Duration.ofMillis(2)))
                .isEqualTo(1);
    }

    @Test
    void minutesStartedSinceMeteringLastCountedAreTheLeasesToo() {
        // Counted in minute 0, sent a second into minute 1: minute 1 isn't counted yet, nor are 2 and 3.
        var sentAt = RUNNING.plusSeconds(61);
        assertThat(MeteredSessions.minutesALeaseStarts(RUNNING, RUNNING.plusSeconds(30), sentAt, TWO_MINUTES))
                .isEqualTo(3);
        assertThat(MeteredSessions.minutesALeaseStarts(RUNNING, sentAt, sentAt, TWO_MINUTES))
                .isEqualTo(2);
    }

    @Test
    void withoutACountSinceTheAttemptRanEveryMinuteItStartsIsTheLeases() {
        var sentAt = RUNNING.plusSeconds(5);
        assertThat(MeteredSessions.minutesALeaseStarts(RUNNING, null, sentAt, TWO_MINUTES))
                .isEqualTo(3);
        assertThat(MeteredSessions.minutesALeaseStarts(RUNNING, RUNNING.minusSeconds(90), sentAt, TWO_MINUTES))
                .isEqualTo(3);
    }
}
