package com.idleo.controlplane.agentgateway.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** A stopping instance tells its agents to reconnect at once, rather than cutting them when its grace period ends. */
@ControlPlaneTest
class ReconnectOnShutdownTests {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    ApplicationContext context;

    @Autowired
    ReconnectOnShutdown reconnect;

    @LocalGrpcServerPort
    int grpcPort;

    @Test
    void everyOpenStreamEndsUnavailableAndItsPresenceGoes() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        ManagedChannel channel = agent.channel(grpcPort);
        try {
            var stream = TestAgent.open(channel);
            stream.send(TestAgent.hello("1.2.3", TestAgent.GPU_A));
            stream.next(WAIT);
            assertThat(redis.hasKey("agentgateway:presence:" + agent.hostId)).isTrue();

            // As the context would on closing; published for real, it would close the tests' shared context.
            reconnect.onApplicationEvent(new ContextClosedEvent(context));

            var ended = stream.endStatus(WAIT);
            assertThat(ended).isNotNull();
            assertThat(ended.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
            assertThat(ended.getDescription()).isEqualTo(ReconnectOnShutdown.RESTARTING.getDescription());
            assertThat(redis.hasKey("agentgateway:presence:" + agent.hostId)).isFalse();
        } finally {
            channel.shutdownNow();
        }
    }
}
