package com.idleo.controlplane.agentgateway;

import java.util.UUID;

/**
 * A port the agent gateway declares and the sessions module implements (ADR 0019): an agent says how far an
 * attempt's logs have uploaded, the highest chunk seq, in its heartbeats. Statuses carry it too, through {@link
 * SessionStatuses}. Heartbeats aren't acked, so a failure here is logged and the next heartbeat carries it again.
 */
public interface SessionLogProgress {

    void onLogProgress(UUID hostId, UUID sessionId, int attempt, int latestLogSeq);
}
