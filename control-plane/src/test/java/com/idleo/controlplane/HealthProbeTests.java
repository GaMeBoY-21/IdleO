package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@ControlPlaneTest
class HealthProbeTests {

    @Autowired
    MockMvc mvc;

    @Test
    void livenessIsOpenAndUp() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessIsOpenAndUpWithTheDatabase() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void everyOtherActuatorEndpointIsClosed() throws Exception {
        for (var path : new String[] {"/actuator", "/actuator/health", "/actuator/env", "/actuator/info"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
