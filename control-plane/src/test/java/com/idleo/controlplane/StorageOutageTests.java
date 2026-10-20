package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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

    @Test
    void readinessGoesDownWithStorageWhileLivenessStaysUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

        storage.stop();
        // Storage is asked again only once the last answer is old enough (StorageHealthIndicator.RECHECK_EVERY).
        clock.advance(Duration.ofSeconds(11));

        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                // Nothing about what failed reaches the outside.
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
