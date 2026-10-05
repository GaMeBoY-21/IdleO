package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Valkey going down must not take instances out of the load balancer or drop agents (ADR 0011).
 * Stops its own Valkey, so it never shares a context or containers with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
    ValkeyOutageTests.DedicatedStores.class,
    TestIssuersConfiguration.class,
    TestPkiConfiguration.class,
    TestClockConfiguration.class
})
@DirtiesContext
class ValkeyOutageTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class DedicatedStores {
        @Bean
        @ServiceConnection
        PostgreSQLContainer dedicatedPostgres() {
            return TestcontainersConfiguration.newPostgres();
        }

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> dedicatedValkey() {
            return TestcontainersConfiguration.newValkey();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    @Qualifier("dedicatedValkey")
    GenericContainer<?> valkey;

    @LocalGrpcServerPort
    int grpcPort;

    @Test
    void readinessStaysUpTheStreamStaysOpenAndHeartbeatsCountAsNotRecorded() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var channel = agent.channel(grpcPort);
        try {
            var stream = TestAgent.open(channel);
            stream.send(TestAgent.hello("1.2.3", TestAgent.GPU_A));
            stream.next(Duration.ofSeconds(5));

            valkey.stop();
            stream.send(TestAgent.heartbeat(TestAgent.GPU_A, 60, 10));
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (notRecorded() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }

            assertThat(notRecorded()).isGreaterThanOrEqualTo(1);
            assertThat(stream.endStatus(Duration.ofMillis(500))).isNull();
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        } finally {
            channel.shutdownNow();
        }
    }

    private double notRecorded() {
        var counter =
                meters.find("agents.heartbeats").tag("outcome", "not_recorded").counter();
        return counter == null ? 0 : counter.count();
    }
}
