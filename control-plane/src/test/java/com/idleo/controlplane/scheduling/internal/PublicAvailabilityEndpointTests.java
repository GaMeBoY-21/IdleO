package com.idleo.controlplane.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestHosts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /v1/public/availability} (ADR 0029): open to anyone, cached, and nothing about any one host. */
@ControlPlaneTest
class PublicAvailabilityEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @LocalGrpcServerPort
    int grpcPort;

    @Test
    void anyoneMayReadItAndItIsCachedForAMinute() throws Exception {
        var response = mvc.perform(get("/v1/public/availability"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        assertThat(response.getHeader("Cache-Control")).contains("max-age=60").contains("public");
        var body = JSON.readTree(response.getContentAsString());
        assertThat(body.get("classes")).hasSize(4);
        var again = JSON.readTree(mvc.perform(get("/v1/public/availability"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(again.get("asOf")).isEqualTo(body.get("asOf"));
    }

    @Test
    void aHostOnlineMakesItsClassOnlineButNeverShowsItsPriceAloneOrItsIdentity() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 7);
        try {
            var body = JSON.readTree(mvc.perform(get("/v1/public/availability"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString());

            var twelve = body.get("classes").get(1);
            assertThat(twelve.get("gpuMemoryGb").asInt()).isEqualTo(12);
            // The answer may be up to a minute old, so only what can't be stale: no counts, no hosts, ever.
            var text = body.toString();
            assertThat(text)
                    .doesNotContain(host.id().toString())
                    .doesNotContain("hostsOnline")
                    .doesNotContain("hostId");
            body.get("classes")
                    .forEach(row -> assertThat(row.propertyNames())
                            .containsExactlyInAnyOrder(
                                    "gpuMemoryGb", "online", "livePricePaisePerHour", "typicalPricePaisePerHour"));
        } finally {
            host.disconnect();
        }
    }
}
