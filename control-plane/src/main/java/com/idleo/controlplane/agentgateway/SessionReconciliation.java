package com.idleo.controlplane.agentgateway;

import gpu.agent.v1.Session.RunningSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A port the agent gateway declares and the sessions module implements (ADR 0016): what a reconnecting agent
 * says it's running, from its Hello, compared with the control plane's records before anything else is sent
 * down the new stream.
 */
public interface SessionReconciliation {

    /**
     * @param connectedAt when the new stream connected: only StartSessions delivered before it can be missing
     *     from {@code running}
     */
    void reconcile(UUID hostId, List<RunningSession> running, Instant connectedAt);
}
