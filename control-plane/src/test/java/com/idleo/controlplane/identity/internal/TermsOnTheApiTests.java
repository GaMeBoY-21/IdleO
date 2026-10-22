package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.TestUsers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Terms are enforced on the API as well as in the app (ADR 0038): booking, uploading and adding, linking or pricing a
 * machine refuse a person who hasn't accepted the Terms and privacy notice in force, whether they call with a browser's
 * token or an API key, saying where to accept; reading, stopping, pausing, removing and downloading never do; and a key can
 * never accept for its owner.
 */
@ControlPlaneTest
class TermsOnTheApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LegalSettings legal;

    @Autowired
    IdentityProperties properties;

    private String key(TestUsers.TestUser user) throws Exception {
        return JSON.readTree(mvc.perform(post("/v1/api-keys")
                                .header("Authorization", user.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"scripts\",\"scope\":\"full\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("key")
                .asString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder request, String authorization) {
        return request.header("Authorization", authorization)
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    /** Every call that creates something, as it would be made. */
    private ResultActions creates(String authorization, String call) throws Exception {
        var host = UUID.randomUUID();
        return switch (call) {
            case "book" -> mvc.perform(json(with(post("/v1/sessions"), authorization), "{}"));
            case "upload" -> mvc.perform(json(with(post("/v1/uploads"), authorization), "{}"));
            case "add a machine" -> mvc.perform(json(with(post("/v1/hosts"), authorization), "{\"name\":\"Laptop\"}"));
            case "price a machine" ->
                mvc.perform(json(with(put("/v1/hosts/" + host + "/listing"), authorization), "{}"));
            case "its enrolment code" ->
                mvc.perform(with(post("/v1/hosts/" + host + "/enrolment-code"), authorization));
            case "approve a link" ->
                mvc.perform(json(with(post("/v1/host-links/" + host + "/approve"), authorization), "{}"));
            default -> throw new IllegalArgumentException(call);
        };
    }

    private static final String[] CREATING = {
        "book", "upload", "add a machine", "price a machine", "its enrolment code", "approve a link"
    };

    @Test
    void everyCreatingCallRefusesAPersonWhoHasntAcceptedAndSaysWhereToAccept() throws Exception {
        var person = TestUsers.signUpWithoutTerms(mvc);
        var app = properties.webAppUrl().toString().replaceAll("/+$", "");
        for (var call : CREATING) {
            var refused = creates(person.bearer(), call).andReturn().getResponse();
            assertThat(refused.getStatus()).as(call).isEqualTo(403);
            var problem = JSON.readTree(refused.getContentAsString());
            assertThat(problem.get("type").asString()).as(call).isEqualTo("/problems/terms-not-accepted");
            assertThat(problem.get("detail").asString())
                    .as(call)
                    .contains(app + "/agree/")
                    .contains("sign in");
        }
    }

    @Test
    void anApiKeyIsRefusedTheSameAndCanNeverAcceptForItsOwner() throws Exception {
        var person = TestUsers.signUpWithoutTerms(mvc);
        var key = "Bearer " + key(person);

        // The calls a key may make that create something: refused until the person accepts; the machine calls say a
        // browser first.
        for (var call : new String[] {"book", "upload"}) {
            creates(key, call)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/problems/terms-not-accepted"));
        }
        for (var call : new String[] {"add a machine", "price a machine", "its enrolment code", "approve a link"}) {
            creates(key, call)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/problems/browser-sign-in-required"));
        }
        // The key can't accept: that needs a browser sign-in.
        mvc.perform(json(
                        post("/v1/me/notices").header("Authorization", key),
                        "{\"termsVersion\":\"%s\",\"privacyVersion\":\"%s\",\"ageConfirmed\":true}"
                                .formatted(
                                        legal.notices().termsVersion(),
                                        legal.notices().privacyVersion())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/browser-sign-in-required"));

        // The person accepts in a browser; the same key now gets past the Terms (and meets whatever else the call
        // needs).
        mvc.perform(json(
                        post("/v1/me/notices").header("Authorization", person.bearer()),
                        "{\"termsVersion\":\"%s\",\"privacyVersion\":\"%s\",\"ageConfirmed\":true}"
                                .formatted(
                                        legal.notices().termsVersion(),
                                        legal.notices().privacyVersion())))
                .andExpect(status().isOk());
        for (var call : new String[] {"book", "upload"}) {
            var after =
                    JSON.readTree(creates(key, call).andReturn().getResponse().getContentAsString());
            assertThat(after.get("type").asString()).as(call).isNotEqualTo("/problems/terms-not-accepted");
        }
    }

    @Test
    void aNewVersionAsksAgainBeforeAnythingIsCreatedAndWhoAcceptedItIsNotAsked() throws Exception {
        var person = TestUsers.signUp(mvc);
        var accepted = JSON.readTree(
                creates(person.bearer(), "book").andReturn().getResponse().getContentAsString());
        assertThat(accepted.get("type").asString()).isNotEqualTo("/problems/terms-not-accepted");

        // A new version comes into force: what this person accepted is the old one.
        jdbc.update("update identity.notice_acceptances set terms_version = 'older' where user_id = ?", person.id());
        creates(person.bearer(), "book")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/terms-not-accepted"));
        creates("Bearer " + key(person), "upload")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/terms-not-accepted"));

        // They accept the new one, once, and are through again.
        mvc.perform(json(
                        post("/v1/me/notices").header("Authorization", person.bearer()),
                        "{\"termsVersion\":\"%s\",\"privacyVersion\":\"%s\",\"ageConfirmed\":false}"
                                .formatted(
                                        legal.notices().termsVersion(),
                                        legal.notices().privacyVersion())))
                .andExpect(status().isOk());
        var again = JSON.readTree(
                creates(person.bearer(), "book").andReturn().getResponse().getContentAsString());
        assertThat(again.get("type").asString()).isNotEqualTo("/problems/terms-not-accepted");
    }

    @Test
    void readingStoppingPausingRemovingAndDownloadingAreNeverRefusedForTheTerms() throws Exception {
        var person = TestUsers.signUpWithoutTerms(mvc);
        var authorization = person.bearer();
        var thing = UUID.randomUUID();
        for (var request : new MockHttpServletRequestBuilder[] {
            get("/v1/me"),
            get("/v1/sessions"),
            get("/v1/wallet"),
            get("/v1/hosts"),
            get("/v1/uploads"),
            get("/v1/templates"),
            get("/v1/notifications"),
            get("/v1/api-keys"),
            get("/v1/sessions/" + thing),
            get("/v1/hosts/" + thing),
            post("/v1/sessions/" + thing + "/stop"),
            post("/v1/sessions/" + thing + "/cancel"),
            post("/v1/hosts/" + thing + "/pause"),
            post("/v1/hosts/" + thing + "/resume"),
            get("/v1/sessions/" + thing + "/outputs"),
            get("/v1/me/data"),
            get("/v1/me/exports")
        }) {
            var answer = mvc.perform(request.header("Authorization", authorization)
                            .header("Idempotency-Key", UUID.randomUUID().toString()))
                    .andReturn()
                    .getResponse();
            assertThat(answer.getContentAsString()).as(request.toString()).doesNotContain("terms-not-accepted");
        }
        mvc.perform(get("/v1/sessions").header("Authorization", authorization)).andExpect(status().isOk());
        mvc.perform(post("/v1/sessions/" + thing + "/stop").header("Authorization", authorization))
                .andExpect(status().isNotFound());
    }
}
