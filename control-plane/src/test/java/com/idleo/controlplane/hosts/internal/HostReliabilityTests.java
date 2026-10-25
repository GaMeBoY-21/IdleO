package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.idleo.controlplane.hosts.internal.HostReliability.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The reliability half of the host score: every input moves it the right way, and a host with no history is neutral. */
class HostReliabilityTests {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    private static List<Event> events(boolean good, int count, int daysAgo) {
        var list = new ArrayList<Event>();
        for (int i = 0; i < count; i++) {
            list.add(new Event(daysAgo(daysAgo), good));
        }
        return list;
    }

    private static HostReliability.Breakdown sessions(List<Event> sessions) {
        return HostReliability.of(NOW, sessions, List.of(), List.of(), List.of());
    }

    @Test
    void aHostWithNoHistoryIsNeutralAndNew() {
        var none = sessions(List.of());

        assertThat(none.reliability()).isEqualTo(0.5);
        assertThat(none.label()).isEqualTo(HostReliability.Label.NEW);
        assertThat(none.penalty()).isZero();
    }

    @Test
    void finishedSessionsRaiseItAndLostOnesLowerIt() {
        double neutral = sessions(List.of()).reliability();

        double finished = sessions(events(true, 6, 1)).reliability();
        double lost = sessions(events(false, 6, 1)).reliability();

        assertThat(finished).isGreaterThan(neutral);
        assertThat(lost).isLessThan(neutral);
    }

    @Test
    void itSettlesAsDataArrivesRatherThanJumpingOnTheFirstSession() {
        double one = sessions(events(true, 1, 1)).reliability();
        double twenty = sessions(events(true, 20, 1)).reliability();

        assertThat(one).isBetween(0.5, 0.6);
        assertThat(twenty).isGreaterThan(one).isGreaterThan(0.8);
    }

    @Test
    void aNewHostIsNotBuriedByItsFirstLostSession() {
        var firstLoss = sessions(events(false, 1, 0));

        assertThat(firstLoss.reliability()).isGreaterThan(0.4);
        assertThat(firstLoss.label()).isNotEqualTo(HostReliability.Label.GOOD);
    }

    @Test
    void recentSessionsCountMoreThanOldOnes() {
        var lostLongAgoThenFinished = new ArrayList<>(events(false, 5, 28));
        lostLongAgoThenFinished.addAll(events(true, 5, 1));
        var finishedLongAgoThenLost = new ArrayList<>(events(true, 5, 28));
        finishedLongAgoThenLost.addAll(events(false, 5, 1));

        assertThat(sessions(lostLongAgoThenFinished).reliability())
                .isGreaterThan(sessions(finishedLongAgoThenLost).reliability());
    }

    @Test
    void anObservationCountsForHalfAsMuchEveryTenDays() {
        assertThat(HostReliability.weight(NOW, NOW)).isEqualTo(1.0);
        assertThat(HostReliability.weight(NOW, daysAgo(10))).isCloseTo(0.5, within(1e-9));
        assertThat(HostReliability.weight(NOW, daysAgo(20))).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void passedSelfTestsRaiseItAndFailedOnesLowerIt() {
        double neutral = HostReliability.of(NOW, List.of(), List.of(), List.of(), List.of())
                .reliability();

        double passed = HostReliability.of(NOW, List.of(), events(true, 4, 2), List.of(), List.of())
                .reliability();
        double failed = HostReliability.of(NOW, List.of(), events(false, 4, 2), List.of(), List.of())
                .reliability();

        assertThat(passed).isGreaterThan(neutral);
        assertThat(failed).isLessThan(neutral);
    }

    @Test
    void upheldReportsAndTheAdminFlagLowerItAndTheFlagMoreSo() {
        var good = events(true, 10, 1);
        double clean = sessions(good).reliability();

        var reported = HostReliability.of(NOW, good, List.of(), List.of(daysAgo(1)), List.of());
        var flagged = HostReliability.of(NOW, good, List.of(), List.of(), List.of(daysAgo(1)));

        assertThat(reported.reliability()).isLessThan(clean);
        assertThat(flagged.reliability()).isLessThan(reported.reliability());
        assertThat(reported.reportsUpheld()).isEqualTo(1);
        assertThat(flagged.flags()).isEqualTo(1);
    }

    @Test
    void aMarkFadesAsItGetsOlder() {
        var good = events(true, 10, 1);
        var fresh = HostReliability.of(NOW, good, List.of(), List.of(), List.of(daysAgo(0)));
        var old = HostReliability.of(NOW, good, List.of(), List.of(), List.of(daysAgo(25)));

        assertThat(old.reliability()).isGreaterThan(fresh.reliability());
    }

    @Test
    void thePenaltyIsCappedAndTheScoreStaysBetweenZeroAndOne() {
        var flags = events(false, 20, 0).stream().map(Event::at).toList();
        var worst = HostReliability.of(NOW, events(false, 30, 0), events(false, 10, 0), flags, flags);

        assertThat(worst.penalty()).isEqualTo(HostReliability.MAX_PENALTY);
        assertThat(worst.reliability()).isBetween(0.0, 1.0);

        var best = sessions(events(true, 500, 0));
        assertThat(best.reliability()).isBetween(0.0, 1.0);
    }

    @Test
    void theOwnersWordFollowsTheScoreAndHowMuchThereIsToGoOn() {
        assertThat(sessions(events(true, 30, 1)).label()).isEqualTo(HostReliability.Label.GOOD);
        assertThat(sessions(events(true, 2, 1)).label()).isEqualTo(HostReliability.Label.NEW);
        var mixed = new ArrayList<>(events(true, 6, 1));
        mixed.addAll(events(false, 6, 1));
        assertThat(sessions(mixed).label()).isEqualTo(HostReliability.Label.FAIR);
    }

    @Test
    void theSameInputGivesTheSameScore() {
        var input = new ArrayList<>(events(true, 7, 3));
        input.addAll(events(false, 2, 1));

        assertThat(sessions(input)).isEqualTo(sessions(List.copyOf(input)));
    }
}
