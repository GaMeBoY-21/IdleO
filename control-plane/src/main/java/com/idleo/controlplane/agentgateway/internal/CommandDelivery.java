package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.agentgateway.SessionCredentials;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers queued commands to the agents whose streams are on this instance (ADR 0015). After a command commits, a hint goes to the Valkey channel of the instance that holds the
 * host's stream, from its presence record; that instance sends the host's pending, unexpired commands in
 * creation order. The same happens right after a stream's Welcome, and every few seconds for every
 * connected host, so a lost hint costs seconds, never a command.
 *
 * <p>A StartSession is stored without credentials. At each delivery the sessions module mints fresh ones
 * through {@link SessionCredentials} (ADR 0016); they exist only in the message on the wire.
 */
@Component
@ConditionalOnBooleanProperty(name = "spring.grpc.server.enabled", matchIfMissing = true)
class CommandDelivery implements MessageListener {

    static final String CHANNEL_PREFIX = "agentgateway:commands:";

    private static final Logger log = LoggerFactory.getLogger(CommandDelivery.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ConnectedAgents connected;
    private final Presence presence;
    private final StringRedisTemplate redis;
    private final AgentGatewayProperties properties;
    private final ObjectProvider<SessionCredentials> credentials;
    private final Clock clock;

    CommandDelivery(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            ConnectedAgents connected,
            Presence presence,
            StringRedisTemplate redis,
            AgentGatewayProperties properties,
            ObjectProvider<SessionCredentials> credentials,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.connected = connected;
        this.presence = presence;
        this.redis = redis;
        this.properties = properties;
        this.credentials = credentials;
        this.clock = clock;
    }

    static String channel(UUID instanceId) {
        return CHANNEL_PREFIX + instanceId;
    }

    /** Tells the instance holding the host's stream that commands are waiting. Never throws. */
    void hint(UUID hostId) {
        try {
            presence.instanceOf(hostId)
                    .ifPresent(instance -> redis.convertAndSend(channel(instance), hostId.toString()));
        } catch (RuntimeException valkeyDown) {
            log.debug("Couldn't send a delivery hint for host {}; the sweep will deliver", hostId, valkeyDown);
        }
    }

    /** A hint for one of this instance's hosts. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            deliverPending(UUID.fromString(new String(message.getBody(), StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            log.warn("Delivery after a hint failed; the sweep will retry", e);
        }
    }

    /** Sends the host's waiting commands down its stream, if the stream is on this instance. */
    void deliverPending(UUID hostId) {
        var stream = connected.get(hostId);
        if (stream == null) {
            return;
        }
        transactions.executeWithoutResult(status -> {
            var now = Instant.now(clock);
            var due = jdbc.sql("""
                            select id, payload from agentgateway.agent_commands
                            where host_id = :hostId
                              and (expires_at is null or expires_at > :now)
                              and (status = 'pending'
                                   or (status = 'sent' and requires_ack and sent_at < :resendBefore))
                            order by created_at
                            for update skip locked
                            """)
                    .param("hostId", hostId)
                    .param("now", Timestamp.from(now))
                    .param("resendBefore", Timestamp.from(now.minus(properties.commandResendAfter())))
                    .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getBytes("payload")})
                    .list();
            for (var command : due) {
                var id = (UUID) command[0];
                OpenStreamResponse message;
                try {
                    message = OpenStreamResponse.parseFrom((byte[]) command[1]);
                } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    throw new IllegalStateException("A queued command doesn't parse", e);
                }
                if (message.hasStartSession() || message.hasRefreshCredentials()) {
                    var filled = withCredentials(hostId, id, message);
                    if (filled == null) {
                        continue;
                    }
                    message = filled;
                }
                if (!stream.deliver(message)) {
                    return;
                }
                var sentAt = Instant.now(clock);
                jdbc.sql("""
                                update agentgateway.agent_commands
                                set status = 'sent', attempts = attempts + 1, sent_at = :now
                                where id = :id
                                """).param("now", Timestamp.from(now)).param("id", id).update();
                if (message.hasStartSession()) {
                    credentials.getObject().delivered(hostId, message.getStartSession(), sentAt);
                } else if (message.hasRefreshCredentials()) {
                    credentials.getObject().refreshed(hostId, message.getRefreshCredentials(), sentAt);
                }
            }
        });
    }

    /**
     * The StartSession or RefreshCredentials with freshly minted credentials; null if it mustn't go now:
     * withdrawn because its attempt is no longer current, or left queued because storage couldn't mint them.
     */
    private @Nullable OpenStreamResponse withCredentials(UUID hostId, UUID commandId, OpenStreamResponse message) {
        boolean start = message.hasStartSession();
        var sessionId = start
                ? message.getStartSession().getSessionId()
                : message.getRefreshCredentials().getSessionId();
        int attempt = start
                ? message.getStartSession().getAttempt()
                : message.getRefreshCredentials().getAttempt();
        java.util.Optional<gpu.agent.v1.Session.Credentials> minted;
        try {
            minted = start
                    ? credentials.getObject().mint(hostId, message.getStartSession())
                    : credentials.getObject().mintRefresh(hostId, message.getRefreshCredentials());
        } catch (RuntimeException e) {
            log.warn(
                    "Couldn't mint storage credentials for session {} attempt {}; delivery retries shortly",
                    sessionId,
                    attempt,
                    e);
            return null;
        }
        if (minted.isEmpty()) {
            jdbc.sql("update agentgateway.agent_commands set status = 'withdrawn' where id = :id")
                    .param("id", commandId)
                    .update();
            log.info(
                    "Withdrew the {} for session {} attempt {}: it's no longer current",
                    start ? "StartSession" : "RefreshCredentials",
                    sessionId,
                    attempt);
            return null;
        }
        return start
                ? message.toBuilder()
                        .setStartSession(message.getStartSession().toBuilder().setCredentials(minted.get()))
                        .build()
                : message.toBuilder()
                        .setRefreshCredentials(
                                message.getRefreshCredentials().toBuilder().setCredentials(minted.get()))
                        .build();
    }

    /** Catches anything a hint missed, and marks commands that expired undelivered. */
    @Scheduled(fixedDelayString = "${agentgateway.command-sweep-interval}")
    void sweep() {
        try {
            jdbc.sql("""
                            update agentgateway.agent_commands set status = 'expired'
                            where status = 'pending' and expires_at <= :now
                            """).param("now", Timestamp.from(Instant.now(clock))).update();
            for (var hostId : connected.hostIds()) {
                deliverPending(hostId);
            }
        } catch (RuntimeException e) {
            log.warn("The command sweep failed; it runs again shortly", e);
        }
    }
}
