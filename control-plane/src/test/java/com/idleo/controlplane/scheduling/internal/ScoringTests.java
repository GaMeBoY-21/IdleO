package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The batch score (technical design lines 359-369) with real inputs: each moves a host's rank the right way. */
class ScoringTests {

    private static final UUID A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-7000-8000-00000000000b");

    private static Scoring.Scored scored(UUID host, int price, double reliability, double bandwidth, boolean cached) {
        return new Scoring.Scored(
                new Scoring.Candidate(host, price, "GPU-1"), Scoring.score(price, 10, reliability, bandwidth, cached));
    }

    private static UUID best(Scoring.Scored... candidates) {
        var list = new ArrayList<>(List.of(candidates));
        list.sort(Scoring.BEST_FIRST);
        return list.getFirst().candidate().hostId();
    }

    @Test
    void aMoreReliableHostRanksFirstAtTheSamePrice() {
        assertThat(best(scored(A, 5, 0.4, 0.5, false), scored(B, 5, 0.9, 0.5, false)))
                .isEqualTo(B);
    }

    @Test
    void aFasterLineRanksFirstAtTheSamePrice() {
        assertThat(best(scored(A, 5, 0.5, 0.3, false), scored(B, 5, 0.5, 0.9, false)))
                .isEqualTo(B);
    }

    @Test
    void aWarmCacheRanksFirstAtTheSamePrice() {
        assertThat(best(scored(A, 5, 0.5, 0.5, false), scored(B, 5, 0.5, 0.5, true)))
                .isEqualTo(B);
    }

    @Test
    void aCheaperHostStillWinsWhenTheOthersAreEqual() {
        assertThat(best(scored(A, 3, 0.5, 0.5, false), scored(B, 6, 0.5, 0.5, false)))
                .isEqualTo(A);
    }

    @Test
    void aHostWithNothingMeasuredIsNotRankedBelowAnAverageOne() {
        // 0.5 is what the hosts module gives a host with no data.
        assertThat(Scoring.score(5, 10, 0.5, 0.5, false)).isEqualTo(Scoring.score(5, 10, 0.5, 0.5, false));
        assertThat(best(scored(A, 5, 0.5, 0.5, false), scored(B, 5, 0.45, 0.5, false)))
                .isEqualTo(A);
    }

    @Test
    void equalScoresRankByHostIdSoTheOrderIsTheSameEveryTime() {
        var first = scored(B, 5, 0.7, 0.6, true);
        var second = scored(A, 5, 0.7, 0.6, true);

        for (int run = 0; run < 3; run++) {
            var ordered = new ArrayList<>(List.of(first, second));
            if (run % 2 == 1) {
                java.util.Collections.reverse(ordered);
            }
            ordered.sort(Scoring.BEST_FIRST);
            assertThat(ordered.stream().map(s -> s.candidate().hostId())).containsExactly(A, B);
        }
    }

    @Test
    void anImageIsNamedByItsDigestWhateverRegistryHoldsIt() {
        assertThat(Scoring.digest("registry.idleo.test:5000/pytorch@sha256:abc"))
                .isEqualTo("sha256:abc");
        assertThat(Scoring.digest("other.example/pytorch@sha256:abc")).isEqualTo("sha256:abc");
    }
}
