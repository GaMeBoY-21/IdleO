package com.idleo.controlplane.hosts.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The reliability half of the host score, from 0 to 1 (docs/plan/execution-plan.md "Host score"). Pure arithmetic over
 * the last 30 days of what happened, so every input's effect is a test.
 *
 * <p>Each observation counts for less the older it is (halving every {@link #HALF_LIFE}). The session record and the
 * self-test record are each a Bayesian average: a prior of {@link #SESSION_PRIOR} sessions (and {@link #SELF_TEST_PRIOR}
 * self-tests) that went half right, so a host with no history is exactly 0.5 and settles as data arrives. Upheld
 * reports and the admin's trust flag are taken off the result, more for a flag, and never more than {@link
 * #MAX_PENALTY} together.
 */
final class HostReliability {

    static final Duration WINDOW = Duration.ofDays(30);
    static final Duration HALF_LIFE = Duration.ofDays(10);
    static final double NEUTRAL = 0.5;
    static final double SESSION_PRIOR = 5;
    static final double SELF_TEST_PRIOR = 2;
    static final double SESSIONS_SHARE = 0.8;
    static final double REPORT_PENALTY = 0.10;
    static final double FLAG_PENALTY = 0.15;
    static final double MAX_PENALTY = 0.5;
    /** Fewer sessions than this in the window and the host is still "New". */
    static final int SESSIONS_TO_BE_KNOWN = 5;

    static final double GOOD_FROM = 0.7;
    static final double FAIR_FROM = 0.45;

    private HostReliability() {}

    /** One thing that happened at {@code at}: a session finished or lost, a self-test passed or failed, a mark. */
    record Event(Instant at, boolean good) {}

    /** What the score was made of, kept beside it for the owner's words and the admin's numbers. */
    record Breakdown(
            double reliability,
            double sessionScore,
            double selfTestScore,
            double penalty,
            int sessionsFinished,
            int sessionsLost,
            int selfTestsPassed,
            int selfTestsFailed,
            int reportsUpheld,
            int flags) {

        /** The word the owner sees. */
        Label label() {
            boolean known = sessionsFinished + sessionsLost >= SESSIONS_TO_BE_KNOWN;
            if (reliability >= GOOD_FROM) {
                return Label.GOOD;
            }
            if (!known && reliability >= FAIR_FROM) {
                return Label.NEW;
            }
            return Label.FAIR;
        }
    }

    enum Label {
        GOOD,
        FAIR,
        NEW
    }

    static Breakdown of(
            Instant now, List<Event> sessions, List<Event> selfTests, List<Instant> reports, List<Instant> flags) {
        double sessionScore = bayesian(now, sessions, SESSION_PRIOR);
        double selfTestScore = bayesian(now, selfTests, SELF_TEST_PRIOR);
        double penalty = Math.min(
                MAX_PENALTY,
                REPORT_PENALTY
                                * reports.stream()
                                        .mapToDouble(at -> weight(now, at))
                                        .sum()
                        + FLAG_PENALTY
                                * flags.stream()
                                        .mapToDouble(at -> weight(now, at))
                                        .sum());
        double reliability = clamp(SESSIONS_SHARE * sessionScore + (1 - SESSIONS_SHARE) * selfTestScore - penalty);
        return new Breakdown(
                reliability,
                sessionScore,
                selfTestScore,
                penalty,
                (int) sessions.stream().filter(Event::good).count(),
                (int) sessions.stream().filter(event -> !event.good()).count(),
                (int) selfTests.stream().filter(Event::good).count(),
                (int) selfTests.stream().filter(event -> !event.good()).count(),
                reports.size(),
                flags.size());
    }

    /** How much something that happened at {@code at} counts, from 1 now to a half every {@link #HALF_LIFE}. */
    static double weight(Instant now, Instant at) {
        double ageDays = Math.max(0, Duration.between(at, now).toSeconds()) / 86_400.0;
        return Math.pow(0.5, ageDays / (HALF_LIFE.toSeconds() / 86_400.0));
    }

    private static double bayesian(Instant now, List<Event> events, double prior) {
        double good = 0;
        double all = 0;
        for (var event : events) {
            double weight = weight(now, event.at());
            all += weight;
            if (event.good()) {
                good += weight;
            }
        }
        return (prior * NEUTRAL + good) / (prior + all);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
