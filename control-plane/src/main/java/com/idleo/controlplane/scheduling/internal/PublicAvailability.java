package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.hosts.SchedulableHosts;
import com.idleo.controlplane.sessions.GpuClasses;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * What a visitor who hasn't signed in may know about the GPUs on offer: for each memory class, whether any host is
 * online and, once at least {@link #MIN_HOSTS} are, the lowest and highest price an hour. Below that, one host's
 * price would be that host's, so only the class's typical range (the one the hosts' price suggestion starts from,
 * {@code scheduling.price-band-defaults}) is given, and it is labelled as typical. It never counts hosts and never
 * names one. The answer is worked out at most once a minute, whoever asks.
 */
@Component
@EnableConfigurationProperties(PublicSiteProperties.class)
class PublicAvailability {

    /** Fewest online hosts in a class before its live prices are shown. */
    static final int MIN_HOSTS = 3;

    static final Duration TTL = Duration.ofSeconds(60);

    /** Paise an hour. */
    record HourlyPriceRange(int min, int max) {}

    /**
     * One memory class.
     *
     * @param online some host of this class is online right now
     * @param livePricePaisePerHour the lowest and highest price among the online hosts; only with at least three
     * @param typicalPricePaisePerHour what a host of this class typically asks, from configuration
     */
    record PublicClassAvailability(
            int gpuMemoryGb,
            boolean online,
            @Nullable HourlyPriceRange livePricePaisePerHour,
            HourlyPriceRange typicalPricePaisePerHour) {}

    /**
     * @param asOf when this was worked out
     * @param startingCreditPaise a new account's credit, if one has been decided
     */
    record PublicAvailabilityAnswer(
            Instant asOf,
            List<PublicClassAvailability> classes,
            @Nullable Long startingCreditPaise) {}

    private record Cached(Instant at, PublicAvailabilityAnswer answer) {}

    private final CandidateFinder candidates;
    private final SchedulableHosts hosts;
    private final SchedulingProperties scheduling;
    private final PublicSiteProperties site;
    private final Clock clock;
    private final AtomicReference<@Nullable Cached> cached = new AtomicReference<>();

    PublicAvailability(
            CandidateFinder candidates,
            SchedulableHosts hosts,
            SchedulingProperties scheduling,
            PublicSiteProperties site,
            Clock clock) {
        this.candidates = candidates;
        this.hosts = hosts;
        this.scheduling = scheduling;
        this.site = site;
        this.clock = clock;
    }

    PublicAvailabilityAnswer get() {
        var now = Instant.now(clock);
        var held = cached.get();
        if (held != null && Duration.between(held.at(), now).compareTo(TTL) < 0) {
            return held.answer();
        }
        var fresh = new Cached(now, compute(now));
        cached.set(fresh);
        return fresh.answer();
    }

    private PublicAvailabilityAnswer compute(Instant now) {
        var pricesByClass = new TreeMap<Integer, List<Integer>>();
        for (var looked : candidates.look(hosts.listedForBatch(), 0)) {
            if (!looked.online()) {
                continue;
            }
            var gb = classOf(looked.candidate().gpuVramMb());
            if (gb != null) {
                pricesByClass
                        .computeIfAbsent(gb, ignored -> new ArrayList<>())
                        .add(looked.candidate().pricePaisePerMin());
            }
        }
        return new PublicAvailabilityAnswer(now, rows(pricesByClass, scheduling, site), site.startingCreditPaise());
    }

    /** The row for every class, whether or not any host is online: the page lists them all. */
    static List<PublicClassAvailability> rows(
            Map<Integer, List<Integer>> onlinePricesByClass,
            SchedulingProperties scheduling,
            PublicSiteProperties site) {
        var rows = new ArrayList<PublicClassAvailability>();
        for (int gb : GpuClasses.GB) {
            var prices = onlinePricesByClass.getOrDefault(gb, List.of());
            var typical = scheduling.priceBandDefaults().get(gb);
            rows.add(new PublicClassAvailability(
                    gb,
                    !prices.isEmpty(),
                    prices.size() < MIN_HOSTS ? null : liveRange(prices),
                    new HourlyPriceRange(perHour(typical.lowPaisePerMin()), perHour(typical.highPaisePerMin()))));
        }
        return rows;
    }

    private static HourlyPriceRange liveRange(List<Integer> prices) {
        return new HourlyPriceRange(
                perHour(prices.stream().mapToInt(Integer::intValue).min().orElseThrow()),
                perHour(prices.stream().mapToInt(Integer::intValue).max().orElseThrow()));
    }

    private static int perHour(int paisePerMin) {
        return paisePerMin * 60;
    }

    /** The largest class the card's memory covers; null below the smallest, which no session can use. */
    static @Nullable Integer classOf(int vramMb) {
        return GpuClasses.GB.reversed().stream()
                .filter(gb -> vramMb >= GpuClasses.mb(gb))
                .findFirst()
                .orElse(null);
    }
}
