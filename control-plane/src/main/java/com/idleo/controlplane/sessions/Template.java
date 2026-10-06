package com.idleo.controlplane.sessions;

import java.time.Duration;
import java.util.List;

/**
 * One entry of the template catalog (templates/catalog.yaml, ADR 0015): what a renter can run, and the
 * limits its container gets.
 */
public record Template(
        String id,
        String name,
        String image,
        List<String> args,
        int minVramMb,
        Duration defaultMaxRuntime,
        Duration maxRuntimeCap,
        int memoryMb,
        int cpuMillicores,
        int pidsLimit,
        int scratchMb,
        Duration checkpointInterval) {

    public Template {
        args = List.copyOf(args);
    }
}
