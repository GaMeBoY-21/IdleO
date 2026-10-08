package com.idleo.controlplane.hosts;

import java.util.List;
import java.util.UUID;

/** What the scheduler needs from hosts (ADR 0015). */
public interface SchedulableHosts {

    /**
     * An active host listed for batch work, and the GPU a session would run on: its largest reported GPU
     * that isn't gone (ties by uuid).
     */
    record Candidate(
            UUID hostId, int pricePaisePerMin, int gpuTempCapC, String gpuUuid, int gpuVramMb, String gpuModel) {}

    /**
     * Active hosts listed for batch work at or under {@code maxPricePaisePerMin}, whose largest reported GPU
     * that isn't gone has at least {@code vramNeededMb}. The one query behind matching, quotes and availability
     * (ADR 0019), so they never disagree about prices.
     */
    List<Candidate> batchCandidates(int maxPricePaisePerMin, int vramNeededMb);

    /** Every active host listed for batch work, at any price and GPU size: {@link #batchCandidates} without limits. */
    default List<Candidate> listedForBatch() {
        return batchCandidates(Integer.MAX_VALUE, 0);
    }

    /**
     * Locks the host's row for the rest of the caller's transaction, if it's still active and no other
     * booking holds it; false, without waiting, otherwise ({@code FOR UPDATE SKIP LOCKED}).
     */
    boolean lockForBooking(UUID hostId);
}
