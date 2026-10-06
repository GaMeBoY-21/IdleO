package com.idleo.controlplane.agentgateway.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.agentgateway.AgentCommands;
import gpu.agent.v1.AgentGateway.Ack;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The command queue (ADR 0015): the right instance is told, the sweep delivers anyway, acks stop resends. */
@ControlPlaneTest
class CommandDeliveryTests {

    static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    AgentCommands commands;

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    RedisConnectionFactory connections;

    @Autowired
    PlatformTransactionManager transactionManager;

    @LocalGrpcServerPort
    int grpcPort;

    private UUID enqueue(UUID hostId, boolean requiresAck) {
        var marker = UUID.randomUUID().toString();
        return new TransactionTemplate(transactionManager)
                .execute(status -> commands.enqueue(
                        hostId,
                        null,
                        "test",
                        OpenStreamResponse.newBuilder().setAck(Ack.newBuilder().setAckedMessageId(marker)),
                        Instant.now().plusSeconds(60),
                        requiresAck));
    }

    private static boolean isMessage(OpenStreamResponse response, UUID messageId) {
        return response.getMessageId().equals(messageId.toString());
    }

    @Test
    void theHintGoesToTheInstanceHoldingTheStreamAndTheSweepDeliversIfItsLost() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var channel = agent.channel(grpcPort);
        var otherInstance = UUID.randomUUID();
        BlockingQueue<String> hints = new LinkedBlockingQueue<>();
        var listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(connections);
        listener.addMessageListener(
                (message, pattern) -> hints.add(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(CommandDelivery.channel(otherInstance)));
        listener.afterPropertiesSet();
        listener.start();
        try {
            var stream = TestAgent.open(channel);
            stream.send(TestAgent.hello("0.1.0", TestAgent.GPU_A));
            stream.next(WAIT);
            // Presence now says another instance holds this host's stream.
            redis.opsForHash().put(Presence.key(agent.hostId), "instance_id", otherInstance.toString());

            var messageId = enqueue(agent.hostId, false);

            assertThat(hints.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo(agent.hostId.toString());
            // That instance doesn't hold the stream, so the hint is lost; the sweep delivers it.
            assertThat(stream.receives(response -> isMessage(response, messageId), WAIT))
                    .isTrue();
            stream.close();
        } finally {
            listener.stop();
            listener.destroy();
            channel.shutdownNow();
        }
    }

    @Test
    void waitingCommandsAreDeliveredRightAfterWelcome() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var messageId = enqueue(agent.hostId, false);
        var channel = agent.channel(grpcPort);
        try {
            var stream = TestAgent.open(channel);
            stream.send(TestAgent.hello("0.1.0", TestAgent.GPU_A));

            assertThat(stream.next(WAIT).hasWelcome()).isTrue();
            assertThat(isMessage(stream.next(WAIT), messageId))
                    .as("delivered straight after Welcome")
                    .isTrue();
            stream.close();
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void aCommandNeedingAnAckIsResentWithTheSameIdUntilAcked() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var channel = agent.channel(grpcPort);
        try {
            var stream = TestAgent.open(channel);
            stream.send(TestAgent.hello("0.1.0", TestAgent.GPU_A));
            stream.next(WAIT);

            var messageId = enqueue(agent.hostId, true);
            stream.await(response -> isMessage(response, messageId), WAIT);
            assertThat(stream.receives(response -> isMessage(response, messageId), WAIT))
                    .as("resent, same message id")
                    .isTrue();

            stream.send(TestAgent.request()
                    .setAck(Ack.newBuilder().setAckedMessageId(messageId.toString()))
                    .build());
            Thread.sleep(500);
            assertThat(stream.receives(response -> isMessage(response, messageId), Duration.ofSeconds(3)))
                    .as("no resend after the ack")
                    .isFalse();
            stream.close();
        } finally {
            channel.shutdownNow();
        }
    }
}
