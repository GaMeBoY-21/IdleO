package com.idleo.controlplane.hosts;

import java.util.List;
import java.util.UUID;

/** What the scheduler needs from hosts (ADR 0015). */
public interface SchedulableHosts {

    /**
     * An active host listed for batch work, and the GPU a session would run on: its largest reported GPU
     * that isn't gone (ties by uuid).
     *
     * @param keepFreeMb the disk its owner keeps free for themselves, in MB (ADR 0023)
     * @param paused its owner paused it, and the pause hasn't ended: no new offers
     * @param reliability its host score's reliability, 0 to 1; 0.5 until it has one (docs/plan/execution-plan.md "Host
     *     score")
     * @param bandwidth its line's speed against the fleet's 90th percentile, 0 to 1; 0.5 until its speed is measured
     */
    record Candidate(
            UUID hostId,
            int pricePaisePerMin,
            int gpuTempCapC,
            String gpuUuid,
            int gpuVramMb,
            String gpuModel,
            int keepFreeMb,
            boolean paused,
            double reliability,
            double bandwidth) {}

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
