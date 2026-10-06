package com.idleo.controlplane.agentgateway;

import gpu.agent.v1.Session.SessionStatus;
import java.util.UUID;

/**
 * A port the agent gateway declares and the sessions module implements (ADR 0016): an agent's report on one
 * attempt of a session. The gateway acks the report only after this returns; if it throws, there's no ack
 * and the agent sends it again.
 */
public interface SessionStatuses {

    void onStatus(UUID hostId, SessionStatus status);
}
