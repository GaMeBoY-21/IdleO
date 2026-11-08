package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.ControlRoom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The funnels by sign-up week (ADR 0056): everyone who signed up in a week is that week's cohort, and each step counts
 * those of them who reached it within 7 and within 30 days of their own sign-up. A host's steps count from the same
 * sign-up, so a share is always of everyone who joined that week. Nothing read or written here: each module says who
 * reached what, and only the counts are kept.
 */
final class ControlRoomCohorts {

    /** How many weeks are shown, this one included. */
    static final int WEEKS = 12;

    /** Which funnel and step is a sign-up: the first step of the renters' funnel. */
    private static final String SIGN_UP_FUNNEL = "renters";

    private static final int SIGN_UP_ORDER = 1;

    private ControlRoomCohorts() {}

    /** When the oldest cohort shown starts. */
    static Instant since(Instant now) {
        return week(now).minusWeeks(WEEKS - 1L).atStartOfDay(ControlRoom.INDIA).toInstant();
    }

    private static LocalDate week(Instant at) {
        return LocalDate.ofInstant(at, ControlRoom.INDIA).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private record StepKey(LocalDate week, String funnel, int order, String step) {}

    static List<ControlRoom.CohortStep> cohorts(List<ControlRoom.Reached> reached, Instant now) {
        var signedUp = new HashMap<UUID, Instant>();
        reached.stream()
                .filter(ControlRoomCohorts::isSignUp)
                .forEach(reach -> signedUp.put(reach.person(), reach.at()));
        var sizes = new TreeMap<LocalDate, Long>();
        var newest = new HashMap<LocalDate, Instant>();
        signedUp.values().forEach(at -> {
            sizes.merge(week(at), 1L, Long::sum);
            newest.merge(week(at), at, (one, other) -> one.isAfter(other) ? one : other);
        });
        record Counts(long[] within) {}
        var counts = new HashMap<StepKey, Counts>();
        // Every step of every funnel is shown for every week, even when nobody reached it.
        var steps = new java.util.LinkedHashSet<StepKey>();
        for (var reach : reached) {
            if (isSignUp(reach)) {
                continue;
            }
            for (var cohort : sizes.keySet()) {
                steps.add(new StepKey(cohort, reach.funnel(), reach.order(), reach.step()));
            }
            var joined = signedUp.get(reach.person());
            if (joined == null || reach.at().isBefore(joined)) {
                continue;
            }
            var key = new StepKey(week(joined), reach.funnel(), reach.order(), reach.step());
            var taken = Duration.between(joined, reach.at());
            var count = counts.computeIfAbsent(key, ignored -> new Counts(new long[2]))
                    .within();
            if (taken.compareTo(Duration.ofDays(7)) <= 0) {
                count[0]++;
            }
            if (taken.compareTo(Duration.ofDays(30)) <= 0) {
                count[1]++;
            }
        }
        var rows = new ArrayList<ControlRoom.CohortStep>();
        for (var key : steps) {
            var count = counts.get(key);
            var last = newest.get(key.week());
            rows.add(new ControlRoom.CohortStep(
                    key.funnel(),
                    key.week(),
                    sizes.get(key.week()),
                    key.order(),
                    key.step(),
                    count == null ? 0 : count.within()[0],
                    count == null ? 0 : count.within()[1],
                    !now.isBefore(last.plus(Duration.ofDays(7))),
                    !now.isBefore(last.plus(Duration.ofDays(30)))));
        }
        rows.sort(Comparator.comparing(ControlRoom.CohortStep::funnel)
                .thenComparing(ControlRoom.CohortStep::week, Comparator.reverseOrder())
                .thenComparing(ControlRoom.CohortStep::order));
        return rows;
    }

    private static boolean isSignUp(ControlRoom.Reached reach) {
        return reach.funnel().equals(SIGN_UP_FUNNEL) && reach.order() == SIGN_UP_ORDER;
    }
}
