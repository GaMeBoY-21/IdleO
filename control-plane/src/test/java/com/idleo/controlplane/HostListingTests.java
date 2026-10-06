package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** A host's listing (ADR 0015): the owner sets it, and its temperature cap reaches the agent. */
@ControlPlaneTest
class HostListingTests {

    @Autowired
    MockMvc mvc;

    @LocalGrpcServerPort
    int grpcPort;

    private org.springframework.test.web.servlet.ResultActions putListing(TestAgent agent, String body)
            throws Exception {
        return mvc.perform(put("/v1/hosts/" + agent.hostId + "/listing")
                .header("Authorization", agent.bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void theOwnerSetsAListingAndTheHostViewShowsIt() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);

        putListing(agent, """
                        {"batchPricePaisePerMin": 22, "gpuTempCapC": 80, "maxConcurrentSessions": 1}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchPricePaisePerMin").value(22));
        putListing(agent, """
                        {"batchPricePaisePerMin": 25, "gpuTempCapC": 78, "maxConcurrentSessions": 1}
                        """).andExpect(status().isOk());

        mvc.perform(get("/v1/hosts/" + agent.hostId).header("Authorization", agent.bearer))
                .andExpect(jsonPath("$.listing.batchPricePaisePerMin").value(25))
                .andExpect(jsonPath("$.listing.gpuTempCapC").value(78))
                .andExpect(jsonPath("$.listing.maxConcurrentSessions").value(1));
    }

    @Test
    void outOfRangeValuesAreRefused() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        for (var body : new String[] {
            "{\"batchPricePaisePerMin\": 0, \"gpuTempCapC\": 80, \"maxConcurrentSessions\": 1}",
            "{\"batchPricePaisePerMin\": 22, \"gpuTempCapC\": 59, \"maxConcurrentSessions\": 1}",
            "{\"batchPricePaisePerMin\": 22, \"gpuTempCapC\": 96, \"maxConcurrentSessions\": 1}",
            "{\"batchPricePaisePerMin\": 22, \"gpuTempCapC\": 80, \"maxConcurrentSessions\": 2}",
            "{\"gpuTempCapC\": 80, \"maxConcurrentSessions\": 1}"
        }) {
            putListing(agent, body).andExpect(status().isBadRequest());
        }
    }

    @Test
    void anotherUsersHostIsNotFound() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var other = TestUsers.signUp(mvc);

        mvc.perform(put("/v1/hosts/" + agent.hostId + "/listing")
                        .header("Authorization", other.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchPricePaisePerMin\": 22, \"gpuTempCapC\": 80, \"maxConcurrentSessions\": 1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void welcomeCarriesTheListingsTemperatureCap() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        TestHosts.list(mvc, agent, 22, 70);
        var channel = agent.channel(grpcPort);
        try {
            var stream = TestAgent.open(channel);
            stream.send(TestAgent.hello("0.1.0", TestAgent.GPU_A));

            assertThat(stream.next(Duration.ofSeconds(5))
                            .getWelcome()
                            .getPolicy()
                            .getGpuTempCapC())
                    .isEqualTo(70);
            stream.close();
        } finally {
            channel.shutdownNow();
        }
    }
}
