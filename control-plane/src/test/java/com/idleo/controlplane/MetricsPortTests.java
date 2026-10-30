package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The meters in Prometheus's format on their own port (MetricsPort, ADR 0046), as the test profile has it like a
 * cluster: that port answers {@code GET /metrics} and nothing else, the public port never answers it, and a scrape is
 * never counted as an API request (canaries are judged by those).
 */
@ControlPlaneTest
class MetricsPortTests {

    private static final int PORT = 19464;

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    private static MockHttpServletRequestBuilder onTheMetricsPort(MockHttpServletRequestBuilder request) {
        return request.with(r -> {
            r.setLocalPort(PORT);
            return r;
        });
    }

    @Test
    void theMetricsPortServesTheMetersInPrometheusFormat() throws Exception {
        meters.counter("sessions.started").increment(0);
        var scrape = mvc.perform(onTheMetricsPort(get("/metrics")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;version=0.0.4;charset=utf-8"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(scrape).contains("# TYPE sessions_started_total counter");
        // The API's timer, with the SLO buckets a canary's 95th percentile is read from.
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        assertThat(mvc.perform(onTheMetricsPort(get("/metrics")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .contains("http_server_requests_seconds_bucket{")
                .contains("le=\"0.5\"");
    }

    @Test
    void thePublicPortNeverAnswersIt() throws Exception {
        mvc.perform(get("/metrics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(Problem.NOT_FOUND.type().toString()));
    }

    @Test
    void theMetricsPortAnswersNothingElse() throws Exception {
        for (var request : new MockHttpServletRequestBuilder[] {
            get("/actuator/health/liveness"), get("/v1/public/availability"), post("/metrics")
        }) {
            mvc.perform(onTheMetricsPort(request)).andExpect(status().isNotFound());
        }
    }

    @Test
    void aScrapeIsNotAnApiRequest() throws Exception {
        mvc.perform(onTheMetricsPort(get("/metrics"))).andExpect(status().isOk());
        var before = requestsCounted();
        for (int i = 0; i < 3; i++) {
            mvc.perform(onTheMetricsPort(get("/metrics"))).andExpect(status().isOk());
        }
        assertThat(requestsCounted()).isEqualTo(before);
    }

    private long requestsCounted() {
        return meters.find("http.server.requests").timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }
}
