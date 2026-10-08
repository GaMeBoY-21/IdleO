package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.ValkeyHints;
import com.idleo.controlplane.agentgateway.AgentCommands;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The agents' command queue (ADR 0015): written in the caller's transaction, delivered after it commits. */
@Service
class AgentCommandService implements AgentCommands {

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final ObjectProvider<CommandDelivery> delivery;

    AgentCommandService(JdbcClient jdbc, IdGenerator ids, Clock clock, ObjectProvider<CommandDelivery> delivery) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.delivery = delivery;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(
            UUID hostId,
            @Nullable UUID sessionId,
            String type,
            OpenStreamResponse.Builder message,
            @Nullable Instant expiresAt,
            boolean requiresAck) {
        var messageId = ids.newId();
        var payload = message.setMessageId(messageId.toString()).build().toByteArray();
        jdbc.sql("""
                        insert into agentgateway.agent_commands
                            (id, host_id, session_id, type, payload, message_id, requires_ack, status, attempts,
                             expires_at, created_at)
                        values (:id, :hostId, :sessionId, :type, :payload, :messageId, :requiresAck, 'pending', 0,
                                :expiresAt, :now)
                        """)
                .param("id", ids.newId())
                .param("hostId", hostId)
                .param("sessionId", sessionId)
                .param("type", type)
                .param("payload", payload)
                .param("messageId", messageId)
                .param("requiresAck", requiresAck)
                .param("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                .param("now", Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)))
                .update();
        // After commit, tell whichever instance holds the host's stream. Only a hint: the sweep and the next
        // Welcome deliver it anyway.
        ValkeyHints.afterCommit(() -> delivery.ifAvailable(commands -> commands.hint(hostId)));
        return messageId;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void withdraw(UUID sessionId, String type) {
        jdbc.sql("""
                        update agentgateway.agent_commands set status = 'withdrawn'
                        where session_id = :sessionId and type = :type and status in ('pending', 'sent')
                        """).param("sessionId", sessionId).param("type", type).update();
    }

    /**
     * The host's agent acknowledged a message that needed it. The ack can overtake the commit that marks the
     * command sent, so a pending one matches too: the update then waits for that commit and checks the row
     * again. Message ids are never shown to anyone but the host they're sent to.
     */
    void acked(UUID hostId, UUID messageId) {
        jdbc.sql("""
                        update agentgateway.agent_commands set status = 'acked', acked_at = :now
                        where message_id = :messageId and host_id = :hostId and requires_ack
                          and status in ('pending', 'sent')
                        """)
                .param("now", Timestamp.from(Instant.now(clock)))
                .param("messageId", messageId)
                .param("hostId", hostId)
                .update();
    }
}
