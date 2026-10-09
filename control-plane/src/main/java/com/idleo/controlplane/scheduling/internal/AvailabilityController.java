package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.hosts.SchedulableHosts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What's available now (ADR 0019, product surface 11.4): one row per GPU model and memory size among hosts that are
 * active, listed and online, with how many are free and their prices. Renters choose a template and a maximum price,
 * never a host, so no host is named. Signed-in only for now; the public, cached version comes with the pricing page.
 */
@RestController
@RequestMapping("/v1/availability")
class AvailabilityController {

    private final SchedulableHosts hosts;
    private final CandidateFinder candidates;

    AvailabilityController(SchedulableHosts hosts, CandidateFinder candidates) {
        this.hosts = hosts;
        this.candidates = candidates;
    }

    /** Prices in paise a minute; the median is the lower of the two middle prices when there's an even number. */
    record PriceStats(int min, int median, int max) {}

    /**
     * One kind of GPU.
     *
     * @param hostsOnline active, listed hosts with this GPU whose agent is online
     * @param hostsIdle those that could take a session now
     * @param pricePaisePerMin among the online ones
     */
    record GpuAvailability(String gpuModel, int vramMb, int hostsOnline, int hostsIdle, PriceStats pricePaisePerMin) {}

    record Availability(List<GpuAvailability> gpus) {}

    @GetMapping
    Availability get() {
        record Kind(String model, int vramMb) {}
        var byKind = new TreeMap<Kind, List<CandidateFinder.Looked>>(
                Comparator.comparing(Kind::model).thenComparingInt(Kind::vramMb));
        // Free means free for the smallest session: online, current, owner away, cool, idle, its disk known.
        for (var looked : candidates.look(hosts.listedForBatch(), 0)) {
            if (looked.online()) {
                byKind.computeIfAbsent(
                                new Kind(
                                        looked.candidate().gpuModel(),
                                        looked.candidate().gpuVramMb()),
                                kind -> new ArrayList<>())
                        .add(looked);
            }
        }
        var rows = new ArrayList<GpuAvailability>();
        for (Map.Entry<Kind, List<CandidateFinder.Looked>> entry : byKind.entrySet()) {
            var prices = entry.getValue().stream()
                    .mapToInt(looked -> looked.candidate().pricePaisePerMin())
                    .sorted()
                    .toArray();
            rows.add(new GpuAvailability(
                    entry.getKey().model(),
                    entry.getKey().vramMb(),
                    prices.length,
                    (int) entry.getValue().stream()
                            .filter(CandidateFinder.Looked::free)
                            .count(),
                    new PriceStats(prices[0], prices[(prices.length - 1) / 2], prices[prices.length - 1])));
        }
        return new Availability(rows);
    }
}
