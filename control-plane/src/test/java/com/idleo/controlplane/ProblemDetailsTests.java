package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@ControlPlaneTest
class ProblemDetailsTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProblemDetailsSecurityHandler securityHandler;

    @Test
    void unauthenticatedRequestGets401ProblemDetail() throws Exception {
        mvc.perform(get("/v1/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    @WithMockUser
    void unknownPathGets404ProblemDetail() throws Exception {
        mvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"));
    }

    @Test
    void wrongMethodGets405ProblemDetail() throws Exception {
        mvc.perform(post("/actuator/health/liveness"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.title").value("Method Not Allowed"));
    }

    @Test
    @WithMockUser
    void unexpectedFailureGets500WithoutMessageOrStackTrace() throws Exception {
        var body = mvc.perform(get(ThrowingController.PATH))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(ThrowingController.SECRET_MESSAGE)
                .doesNotContain("IllegalStateException")
                .doesNotContain("com.idleo")
                .doesNotContainIgnoringCase("trace");
    }

    @Test
    void accessDeniedGets403ProblemDetail() throws Exception {
        var request = new MockHttpServletRequest("GET", "/v1/anything");
        var response = new MockHttpServletResponse();

        securityHandler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"status\":403").contains("Access is denied.");
    }
}
