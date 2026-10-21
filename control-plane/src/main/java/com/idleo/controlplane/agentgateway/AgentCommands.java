package com.idleo.controlplane.agentgateway;

import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one durable queue for everything the control plane sends an agent (ADR 0015, technical design lines
 * 34 and 177-189). A command is written in the caller's transaction and delivered after it commits, to
 * whichever instance holds the host's stream; a periodic sweep catches anything a hint missed.
 */
public interface AgentCommands {

    /**
     * Queues {@code message} for the host's agent, inside the caller's transaction. The message id is set
     * here and reused on every resend.
     *
     * @param type what it is, for example {@code offer}
     * @param expiresAt never delivered after this; null for no expiry
     * @param requiresAck kept and resent until the agent's Ack; false for messages answered another way,
     *     such as an Offer (answered by OfferReply)
     * @return the message id
     */
    UUID enqueue(
            UUID hostId,
            @Nullable UUID sessionId,
            String type,
            OpenStreamResponse.Builder message,
            @Nullable Instant expiresAt,
            boolean requiresAck);

    /** Stops delivery of the session's commands of {@code type} that haven't been delivered, or acked. */
    void withdraw(UUID sessionId, String type);

    /**
     * Queues {@code message} for the host's agent in place of any command of the same {@code type} for that host not
     * yet delivered or acked, inside the caller's transaction; kept and resent until the agent's Ack. For messages
     * where only the latest matters, such as HostStatus and UpdateAgent.
     *
     * @return the message id
     */
    UUID replace(UUID hostId, String type, OpenStreamResponse.Builder message);

    /** Stops delivery of the host's commands of {@code type} that haven't been delivered, or acked. */
    void withdrawFor(UUID hostId, String type);

    /**
     * Deletes every command for the session, whatever its state (ADR 0037): a StartSession carries the renter's
     * parameters and input links, so erasing their account takes it. Called only for a session that has ended.
     */
    void forget(UUID sessionId);
}
