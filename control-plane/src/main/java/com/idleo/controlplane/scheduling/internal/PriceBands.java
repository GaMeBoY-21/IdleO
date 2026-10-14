package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.sessions.GpuClasses;
import java.util.Arrays;
import java.util.List;

/**
 * The price range suggested to a host (ADR 0023): the listed prices of the other active hosts with its GPU model,
 * without the top and bottom 10%, and from 5 hosts never fewer than the single highest and lowest, so one odd price
 * can't set an edge. With fewer than {@link #MIN_SAMPLE} such hosts, the default for its GPU memory class.
 */
final class PriceBands {

    static final int MIN_SAMPLE = 3;

    /** From this many hosts, at least the one highest and one lowest price are left out. */
    static final int TRIM_FROM = 5;

    /** @param sampleSize the hosts the range comes from; 0 when it's the class default */
    record Band(int lowPaisePerMin, int highPaisePerMin, int sampleSize) {}

    private PriceBands() {}

    static Band of(List<Integer> peerPrices, int vramMb, SchedulingProperties properties) {
        if (peerPrices.size() < MIN_SAMPLE) {
            var fallback = properties.priceBandDefaults().get(gpuClassGb(vramMb));
            return new Band(fallback.lowPaisePerMin(), fallback.highPaisePerMin(), 0);
        }
        var prices = peerPrices.stream().mapToInt(Integer::intValue).sorted().toArray();
        int trim = prices.length >= TRIM_FROM ? Math.max(1, prices.length / 10) : 0;
        var kept = Arrays.copyOfRange(prices, trim, prices.length - trim);
        return new Band(kept[0], kept[kept.length - 1], kept.length);
    }

    /** The largest class at or below the GPU's memory; the smallest class below it. */
    static int gpuClassGb(int vramMb) {
        return GpuClasses.GB.reversed().stream()
                .filter(gb -> vramMb >= GpuClasses.mb(gb))
                .findFirst()
                .orElse(GpuClasses.GB.getFirst());
    }
}
