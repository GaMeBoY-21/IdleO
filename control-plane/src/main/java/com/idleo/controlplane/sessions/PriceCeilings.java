package com.idleo.controlplane.sessions;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * The price ceiling of a booking that leaves it out (ADR 0021): the highest price among the GPUs that fit right now,
 * by the scheduler's own filters. Declared here and implemented by scheduling, which owns those filters, so the
 * dependency never points from sessions to scheduling (ADR 0015). Booking and its quote both use {@link #ceiling}.
 */
public interface PriceCeilings {

    /**
     * The highest price, in paise a minute, among the free GPUs with at least {@code vramNeededMb}, on hosts with at
     * least {@code diskNeededMb} free; empty if none.
     */
    OptionalInt highestFitting(int vramNeededMb, int diskNeededMb);

    /** The renter's maximum price if they gave one; otherwise the server's, or no-gpu-free when no GPU fits now. */
    default int ceiling(@Nullable Integer requested, int vramNeededMb, int diskNeededMb) {
        if (requested != null) {
            return requested;
        }
        return highestFitting(vramNeededMb, diskNeededMb)
                .orElseThrow(() -> ApiProblem.of(
                        Problem.NO_GPU_FREE,
                        "No GPU with at least " + vramNeededMb + " MB of memory, on a host with " + diskNeededMb
                                + " MB of disk free for your files, is free right now, so there's no price to set a "
                                + "ceiling from. Nothing was created."));
    }
}
