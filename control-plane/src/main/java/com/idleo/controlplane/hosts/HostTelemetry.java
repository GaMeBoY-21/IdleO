package com.idleo.controlplane.hosts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a machine's GPUs have been doing, for its owner's screen (ADR 0023): the latest reading and the last hour, a
 * point a minute. The hosts module declares this port and the agent gateway implements it from its presence records
 * and a short ring in Valkey (ADR 0013). It only feeds the screen, and is never used for billing.
 *
 * <p>Implementations never throw. When Valkey can't be read, there's no reading and no history.
 */
public interface HostTelemetry {

    /** One GPU's reading. */
    record Reading(String gpuUuid, int tempC, int utilPct, int vramUsedMb) {}

    /** Every GPU of the host at one moment. */
    record Point(Instant at, List<Reading> gpus) {}

    /**
     * @param now the latest heartbeat's readings; null when the host's agent isn't connected
     * @param lastHour up to 60 points, oldest first
     */
    record Telemetry(@Nullable Point now, List<Point> lastHour) {}

    Telemetry of(UUID hostId);
}
