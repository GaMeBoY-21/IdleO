package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.TestHosts.ConnectedHost;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What an owner's machine screen reads about its agent and GPUs (ADR 0023). */
@ControlPlaneTest
class HostTelemetryTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @LocalGrpcServerPort
    int grpcPort;

    private ConnectedHost host;

    @AfterEach
    void disconnect() {
        if (host != null) {
            host.disconnect();
        }
    }

    private JsonNode json(String path, String bearer) throws Exception {
        return JSON.readTree(mvc.perform(get(path).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    @Test
    void aConnectedHostShowsItsLatestReadingAndAPointInTheLastHour() throws Exception {
        host = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        var path = "/v1/hosts/" + host.id() + "/telemetry";

        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode telemetry;
        do {
            telemetry = json(path, host.agent().bearer);
            Thread.sleep(100);
        } while ((telemetry.get("now").isNull() || telemetry.get("lastHour").isEmpty())
                && System.nanoTime() < deadline);

        var gpu = telemetry.get("now").get("gpus").get(0);
        assertThat(gpu.get("gpuUuid").asString()).isEqualTo(TestAgent.GPU_A);
        assertThat(gpu.get("tempC").asInt()).isEqualTo(60);
        assertThat(gpu.get("utilPct").asInt()).isEqualTo(5);
        assertThat(telemetry.get("lastHour")).hasSize(1);
        assertThat(telemetry
                        .get("lastHour")
                        .get(0)
                        .get("gpus")
                        .get(0)
                        .get("tempC")
                        .asInt())
                .isEqualTo(60);
    }

    @Test
    void theHostShowsItsAgentVersionAndWhenItCameOnline() throws Exception {
        host = TestHosts.ready(mvc, grpcPort, jdbc, 2);

        var view = json("/v1/hosts/" + host.id(), host.agent().bearer);

        assertThat(view.get("online").asBoolean()).isTrue();
        assertThat(view.get("agentVersion").asString()).isEqualTo("0.1.0");
        assertThat(view.get("updateRequired").asBoolean()).isFalse();
        assertThat(view.get("onlineSince").isNull()).isFalse();
    }

    @Test
    void aHostThatNeverConnectedHasNoReadingNoHistoryAndNoOnlineTime() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);

        var telemetry = json("/v1/hosts/" + agent.hostId + "/telemetry", agent.bearer);
        var view = json("/v1/hosts/" + agent.hostId, agent.bearer);

        assertThat(telemetry.get("now").isNull()).isTrue();
        assertThat(telemetry.get("lastHour")).isEmpty();
        assertThat(view.get("online").asBoolean()).isFalse();
        assertThat(view.get("onlineSince").isNull()).isTrue();
    }

    @Test
    void anotherUsersHostHasNoTelemetry() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var other = TestUsers.signUp(mvc);

        mvc.perform(get("/v1/hosts/" + agent.hostId + "/telemetry").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
    }
}
