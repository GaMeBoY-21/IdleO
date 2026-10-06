package com.idleo.controlplane.hosts;

import java.util.List;
import java.util.UUID;

/** What the scheduler needs from hosts (ADR 0015). */
public interface SchedulableHosts {

    /**
     * An active host listed for batch work, and the GPU a session would run on: its largest reported GPU
     * that isn't gone (ties by uuid).
     */
    record Candidate(UUID hostId, int pricePaisePerMin, int gpuTempCapC, String gpuUuid, int gpuVramMb) {}

    /**
     * Active hosts listed for batch work at or under {@code maxPricePaisePerMin}, whose largest reported GPU
     * that isn't gone has at least {@code vramNeededMb}.
     */
    List<Candidate> batchCandidates(int maxPricePaisePerMin, int vramNeededMb);

    /**
     * Locks the host's row for the rest of the caller's transaction, if it's still active and no other
     * booking holds it; false, without waiting, otherwise ({@code FOR UPDATE SKIP LOCKED}).
     */
    boolean lockForBooking(UUID hostId);
}
