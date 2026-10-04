package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ControlPlaneTest
class MeEndpointTests {

    @Autowired
    MockMvc mvc;

    private String bearer;
    private String etag;

    @BeforeEach
    void signIn() throws Exception {
        bearer = "Bearer " + TestIssuers.get().validToken();
        etag = mvc.perform(get("/v1/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("ETag");
    }

    @Test
    void patchWithoutIfMatchGets428() throws Exception {
        mvc.perform(rename("New Name"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void patchWithStaleETagGets412() throws Exception {
        mvc.perform(rename("First").header("If-Match", etag)).andExpect(status().isOk());

        mvc.perform(rename("Second").header("If-Match", etag))
                .andExpect(status().isPreconditionFailed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void patchWithCurrentETagChangesTheNameAndReturnsANewETag() throws Exception {
        var newEtag = mvc.perform(rename("  Asha  ").header("If-Match", etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Asha"))
                .andReturn()
                .getResponse()
                .getHeader("ETag");

        org.assertj.core.api.Assertions.assertThat(newEtag).isNotNull().isNotEqualTo(etag);
        mvc.perform(get("/v1/me").header("Authorization", bearer))
                .andExpect(header().string("ETag", newEtag))
                .andExpect(jsonPath("$.displayName").value("Asha"));
    }

    @Test
    void invalidDisplayNameGets400NamingTheField() throws Exception {
        mvc.perform(rename("x".repeat(101)).header("If-Match", etag))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
        mvc.perform(rename(" ").header("If-Match", etag))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("displayName"));
    }

    private MockHttpServletRequestBuilder rename(String displayName) {
        return patch("/v1/me")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + displayName + "\"}");
    }
}
