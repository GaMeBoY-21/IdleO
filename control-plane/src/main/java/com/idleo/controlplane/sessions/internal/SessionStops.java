package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.agentgateway.AgentCommands;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.StopReason;
import gpu.agent.v1.Session.StopSession;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells an agent to stop an attempt (ADR 0016), through the command queue, acked. Pointless once the
 * attempt's lease would have stopped it anyway, so it's never delivered after that.
 */
@Component
class SessionStops {

    static final String COMMAND_TYPE = "stop_session";

    private final AgentCommands commands;
    private final LeaseProperties leases;
    private final SessionStore store;
    private final IdGenerator ids;

    SessionStops(AgentCommands commands, LeaseProperties leases, SessionStore store, IdGenerator ids) {
        this.commands = commands;
        this.leases = leases;
        this.store = store;
        this.ids = ids;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void queue(UUID hostId, UUID sessionId, int attempt, StopReason reason) {
        var stop = StopSession.newBuilder()
                .setSessionId(sessionId.toString())
                .setAttempt(attempt)
                .setCommandId(ids.newId().toString())
                .setReason(reason)
                .setCheckpointFirst(false);
        commands.enqueue(
                hostId,
                sessionId,
                COMMAND_TYPE,
                OpenStreamResponse.newBuilder().setStopSession(stop),
                store.now().plus(leases.ttl()).plus(leases.transitAllowance()),
                true);
    }
}
