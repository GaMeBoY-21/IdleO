package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/** Stops its own object storage mid-test, so it never shares a context or container with other tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
    TestcontainersConfiguration.class,
    TestIssuersConfiguration.class,
    TestPkiConfiguration.class,
    TestStorageConfiguration.class,
    TestClockConfiguration.class
})
@DirtiesContext
class StorageOutageTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestStorageConfiguration.StorageContainer storage;

    @Autowired
    MutableClock clock;

    @Autowired
    MeterRegistry meters;

    @Test
    void readinessStaysUpWithoutStorageWhichIsAMeterAndAPlain503() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        assertThat(meters.get("storage.reachable").gauge().value()).isEqualTo(1);

        storage.stop();
        // Storage is asked again only once the last answer is old enough (StorageHealthIndicator.RECHECK_EVERY).
        clock.advance(Duration.ofSeconds(11));

        // Sessions, leases and everything else go on: the instance keeps its traffic (ADR 0036).
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        assertThat(meters.get("storage.reachable").gauge().value()).isZero();
        assertThat(meters.get("health.ready").tag("component", "db").gauge().value())
                .isEqualTo(1);

        // What needs files says so in plain words, with when to try again: an upload is recorded, but storage is where
        // its parts go.
        var renter = TestUsers.signUp(mvc);
        var created = mvc.perform(
                        post("/v1/uploads")
                                .header("Authorization", renter.bearer())
                                .header("Idempotency-Key", "\"storage-outage\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\": \"examples\", \"files\": [{\"path\": \"train.jsonl\", \"sizeBytes\": 10}]}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var upload = created.replaceAll("(?s).*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        mvc.perform(post("/v1/uploads/" + upload + "/part-urls")
                        .header("Authorization", renter.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\": \"train.jsonl\", \"partNumbers\": [1]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("/problems/storage-unavailable"))
                .andExpect(header().exists("Retry-After"));
    }
}
