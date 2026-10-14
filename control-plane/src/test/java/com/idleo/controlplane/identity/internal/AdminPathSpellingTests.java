package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * However an admin path is spelt, it goes through the admin security chain (ADR 0027): a percent-encoded letter, a
 * lower- or mixed-case escape routes to the same admin handler as the plain path, so it must meet the same gate. A
 * normal user or an API key gets the 404 of a page that doesn't exist, never the handler's answer.
 */
@ControlPlaneTest
class AdminPathSpellingTests {

    /** Spellings of /admin/v1/users that Spring routes to the same handler. */
    private static final String[] SPELLINGS = {
        "/admin/v1/users",
        "/%61dmin/v1/users",
        "/admin/v1/%75sers",
        "/ad%6din/v1/users",
        "/%61%64%6D%69%6e/v1/users",
        "/admin/%76%31/users",
        "/ad%6Din/v1/u%73ers",
    };

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode answer(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus())
                .as(request.buildRequest(new org.springframework.mock.web.MockServletContext())
                                .getRequestURI() + " " + response.getContentAsString())
                .isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    @Test
    void noSpellingOfAnAdminPathLetsANormalUserOrAKeyThrough() throws Exception {
        var user = TestUsers.signUp(mvc);
        var key = json.readTree(mvc.perform(post("/v1/api-keys")
                                .header("Authorization", user.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"ci\",\"scope\":\"full\"}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("key")
                .asString();
        var missing = ((ObjectNode) answer(get("/admin/v1/no-such-thing").header("Authorization", user.bearer()), 404))
                .without("instance");

        for (var spelling : SPELLINGS) {
            for (var who : new String[] {user.bearer(), "Bearer " + key}) {
                var found = answer(get(URI.create(spelling)).header("Authorization", who), 404);
                // The same 404 as a page that doesn't exist, and nothing from the handler.
                assertThat(((ObjectNode) found).without("instance"))
                        .as(spelling)
                        .isEqualTo(missing);
                assertThat(found.has("users")).as(spelling).isFalse();
            }
        }
    }

    @Test
    void everySpellingReachesTheHandlerForAnAdminWithASession() throws Exception {
        // The same spellings do route to the handler: the 404s above are the gate's, not a routing miss.
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        for (var spelling : SPELLINGS) {
            assertThat(answer(admin.as(get(URI.create(spelling))), 200).has("users"))
                    .as(spelling)
                    .isTrue();
        }
    }
}
