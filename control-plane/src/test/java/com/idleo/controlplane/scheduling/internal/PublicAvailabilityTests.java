package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idleo.controlplane.hosts.SchedulableHosts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a visitor may be told (ADR 0029), without a server: the three-host rule, the typical range, the minute's cache. */
class PublicAvailabilityTests {

    private static final SchedulingProperties SCHEDULING = new SchedulingProperties(
            Duration.ofSeconds(10),
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofMinutes(10),
            Map.of(
                    8, new SchedulingProperties.BandDefault(8, 14),
                    12, new SchedulingProperties.BandDefault(12, 20),
                    16, new SchedulingProperties.BandDefault(16, 28),
                    24, new SchedulingProperties.BandDefault(24, 42)));

    private static List<PublicAvailability.PublicClassAvailability> rows(Map<Integer, List<Integer>> prices) {
        return PublicAvailability.rows(prices, SCHEDULING, new PublicSiteProperties(null));
    }

    @Test
    void everyClassHasARowAndAnEmptyOneIsOffline() {
        var rows = rows(Map.of());

        assertThat(rows)
                .extracting(PublicAvailability.PublicClassAvailability::gpuMemoryGb)
                .containsExactly(8, 12, 16, 24);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.online()).isFalse();
            assertThat(row.livePricePaisePerHour()).isNull();
        });
    }

    @Test
    void oneOrTwoHostsAreOnlineButTheirPricesAreNotShown() {
        var rows = rows(Map.of(12, List.of(10), 16, List.of(20, 30)));

        assertThat(rows.get(1).online()).isTrue();
        assertThat(rows.get(1).livePricePaisePerHour()).isNull();
        assertThat(rows.get(2).online()).isTrue();
        assertThat(rows.get(2).livePricePaisePerHour()).isNull();
        // What is shown instead is the class's typical range, from configuration, an hour.
        assertThat(rows.get(1).typicalPricePaisePerHour())
                .isEqualTo(new PublicAvailability.HourlyPriceRange(720, 1_200));
    }

    @Test
    void fromThreeHostsTheLivePriceRangeAnHourAppears() {
        var rows = rows(Map.of(12, List.of(12, 18, 15)));

        assertThat(rows.get(1).livePricePaisePerHour()).isEqualTo(new PublicAvailability.HourlyPriceRange(720, 1_080));
    }

    @Test
    void aCardBelongsToTheLargestClassItsMemoryCovers() {
        assertThat(PublicAvailability.classOf(8_188)).isEqualTo(8);
        assertThat(PublicAvailability.classOf(12_282)).isEqualTo(12);
        assertThat(PublicAvailability.classOf(24_564)).isEqualTo(24);
        assertThat(PublicAvailability.classOf(6_144))
                .as("too small for any session")
                .isNull();
    }

    @Test
    void theAnswerIsWorkedOutOnceAMinute() {
        var candidates = mock(CandidateFinder.class);
        var hosts = mock(SchedulableHosts.class);
        when(hosts.listedForBatch()).thenReturn(List.of());
        when(candidates.look(anyList(), anyInt())).thenReturn(List.of());
        var now = new Instant[] {Instant.parse("2026-10-13T10:00:00Z")};
        var clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        var availability =
                new PublicAvailability(candidates, hosts, SCHEDULING, new PublicSiteProperties(10_000L), clock);

        var first = availability.get();
        now[0] = now[0].plusSeconds(59);
        assertThat(availability.get()).isSameAs(first);
        now[0] = now[0].plusSeconds(1);
        var later = availability.get();

        assertThat(later).isNotSameAs(first);
        assertThat(later.startingCreditPaise()).isEqualTo(10_000L);
        verify(candidates, times(2)).look(anyList(), anyInt());
    }
}
