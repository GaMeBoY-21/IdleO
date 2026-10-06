package com.idleo.controlplane.hosts;

import java.util.List;
import java.util.UUID;

/** What the scheduler needs from hosts (ADR 0015). */
public interface SchedulableHosts {

    /** An active host listed for batch work, with a GPU big enough. */
    record Candidate(UUID hostId, int pricePaisePerMin, int gpuTempCapC, int largestGpuVramMb) {}

    /**
     * Active hosts listed for batch work at or under {@code maxPricePaisePerMin}, with a reported GPU that
     * isn't gone and has at least {@code vramNeededMb}.
     */
    List<Candidate> batchCandidates(int maxPricePaisePerMin, int vramNeededMb);

    /**
     * Locks the host's row for the rest of the caller's transaction, if it's still active and no other
     * booking holds it; false, without waiting, otherwise ({@code FOR UPDATE SKIP LOCKED}).
     */
    boolean lockForBooking(UUID hostId);
}
