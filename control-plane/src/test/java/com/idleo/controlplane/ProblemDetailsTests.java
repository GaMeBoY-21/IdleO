package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.MockMvc;

/** Every error carries a type from the catalog (ADR 0019), Spring's own included, with its status unchanged. */
@ControlPlaneTest
class ProblemDetailsTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProblemDetailsSecurityHandler securityHandler;

    @Test
    void unauthenticatedRequestGets401Unauthenticated() throws Exception {
        mvc.perform(get("/v1/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Sign-in required"));
    }

    @Test
    void unknownPathGets404NotFound() throws Exception {
        mvc.perform(get("/no-such-path")
                        .header("Authorization", "Bearer " + TestIssuers.get().validToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void wrongMethodGets405MethodNotAllowed() throws Exception {
        mvc.perform(post("/actuator/health/liveness"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/method-not-allowed"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void malformedJsonGets400MalformedRequest() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(post("/v1/sessions")
                        .header("Authorization", user.bearer())
                        .header(IdempotentRequests.HEADER, "malformed-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/malformed-request"));
    }

    @Test
    void theWrongContentTypeGets415AndAnUnacceptableAcceptGets406() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(post("/v1/sessions")
                        .header("Authorization", user.bearer())
                        .header(IdempotentRequests.HEADER, "text-1")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("/problems/unsupported-media-type"));
        mvc.perform(get("/v1/me").header("Authorization", user.bearer()).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void aPathValueOfTheWrongTypeGets400MalformedRequest() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(get("/v1/sessions/not-a-uuid").header("Authorization", user.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/malformed-request"));
    }

    @Test
    void invalidFieldsGet400ValidationFailedNamingEachField() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(post("/v1/sessions")
                        .header("Authorization", user.bearer())
                        .header(IdempotentRequests.HEADER, "invalid-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\": \"\", \"maxPricePaisePerMin\": 0, \"maxSpendPaise\": 100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("templateId", "maxPricePaisePerMin")));
    }

    @Test
    void aMissingIfMatchGets428VersionRequired() throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(patch("/v1/me")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\": \"Asha\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type").value("/problems/version-required"));
    }

    @Test
    void unexpectedFailureGets500InternalErrorWithoutMessageOrStackTrace() throws Exception {
        var body = mvc.perform(get(ThrowingController.PATH)
                        .header("Authorization", "Bearer " + TestIssuers.get().validToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/internal-error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal error"))
                .andExpect(
                        jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Something failed on our side.")))
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
    void accessDeniedGets403AccessDenied() throws Exception {
        var request = new MockHttpServletRequest("GET", "/v1/anything");
        var response = new MockHttpServletResponse();

        securityHandler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString())
                .contains("\"status\":403")
                .contains("\"type\":\"/problems/access-denied\"")
                .contains("Access is denied.");
    }
}
