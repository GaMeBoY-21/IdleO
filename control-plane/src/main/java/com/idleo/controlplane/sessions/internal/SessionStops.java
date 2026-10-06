package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.agentgateway.AgentCommands;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.StopReason;
import gpu.agent.v1.Session.StopSession;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
        queue(hostId, sessionId, attempt, reason, null);
    }

    /**
     * With a {@code checkpointGrace}, the agent writes a checkpoint first and has that long before it stops
     * anyway; the command is still useful until the grace and the lease after it have passed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void queue(UUID hostId, UUID sessionId, int attempt, StopReason reason, @Nullable Duration checkpointGrace) {
        var stop = StopSession.newBuilder()
                .setSessionId(sessionId.toString())
                .setAttempt(attempt)
                .setCommandId(ids.newId().toString())
                .setReason(reason)
                .setCheckpointFirst(checkpointGrace != null);
        if (checkpointGrace != null) {
            stop.setGracePeriod(SessionSpecs.duration(checkpointGrace));
        }
        var expiresAt = store.now().plus(leases.ttl()).plus(leases.transitAllowance());
        commands.enqueue(
                hostId,
                sessionId,
                COMMAND_TYPE,
                OpenStreamResponse.newBuilder().setStopSession(stop),
                checkpointGrace == null ? expiresAt : expiresAt.plus(checkpointGrace),
                true);
    }
}
