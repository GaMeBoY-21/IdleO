package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Hosts for tests in any package: enrolled, connected with GPUs reported, and listed. */
public final class TestHosts {

    private TestHosts() {}

    /** A connected host: its agent, its open stream and the stream's channel (close it when done). */
    public record ConnectedHost(TestAgent agent, TestAgent.Stream stream, ManagedChannel channel) {

        public UUID id() {
            return agent.hostId;
        }

        public void disconnect() {
            stream.close();
            channel.shutdownNow();
        }
    }

    /** Enrols a host, opens its stream with Hello for {@code gpuUuids}, and waits for Welcome. */
    public static ConnectedHost connect(MockMvc mvc, int grpcPort, String... gpuUuids) throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var channel = agent.channel(grpcPort);
        var stream = TestAgent.open(channel);
        stream.send(TestAgent.hello("0.1.0", gpuUuids.length == 0 ? new String[] {TestAgent.GPU_A} : gpuUuids));
        var welcome = stream.next(Duration.ofSeconds(5));
        assertThat(welcome.hasWelcome()).as("Welcome").isTrue();
        return new ConnectedHost(agent, stream, channel);
    }

    /** Sets the host's listing as its owner. */
    public static void list(MockMvc mvc, TestAgent agent, int pricePaisePerMin, int gpuTempCapC) throws Exception {
        mvc.perform(put("/v1/hosts/" + agent.hostId + "/listing")
                        .header("Authorization", agent.bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchPricePaisePerMin": %d, "gpuTempCapC": %d, "maxConcurrentSessions": 1}
                                """.formatted(pricePaisePerMin, gpuTempCapC)))
                .andExpect(status().isOk());
    }
}
