package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@ControlPlaneTest
class TemplateApiTests {

    @Autowired
    MockMvc mvc;

    @Test
    void signedInUsersSeeTheBundledCatalog() throws Exception {
        var user = TestUsers.signUp(mvc);

        mvc.perform(get("/v1/templates").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[0].id").value("cuda-smoke-test"))
                .andExpect(jsonPath("$.templates[0].minVramMb").value(1024))
                .andExpect(jsonPath("$.templates[0].maxRuntimeCapSeconds").value(900));
        mvc.perform(get("/v1/templates")).andExpect(status().isUnauthorized());
    }
}
