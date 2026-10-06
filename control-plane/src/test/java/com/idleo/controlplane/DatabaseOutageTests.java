package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Stops its own database mid-test, so it never shares a context or container with other tests. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
    DatabaseOutageTests.DedicatedDatabase.class,
    TestIssuersConfiguration.class,
    TestPkiConfiguration.class,
    TestStorageConfiguration.class
})
@DirtiesContext
class DatabaseOutageTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class DedicatedDatabase {
        @Bean
        @ServiceConnection
        PostgreSQLContainer dedicatedPostgres() {
            return TestcontainersConfiguration.newPostgres();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgreSQLContainer postgres;

    @Test
    void readinessGoesDownWithTheDatabaseWhileLivenessStaysUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

        postgres.stop();

        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
