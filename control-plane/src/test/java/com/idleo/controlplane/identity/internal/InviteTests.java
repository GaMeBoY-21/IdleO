package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestUsers;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Access by invitation (ADR 0024): waiting users, codes, and the limit on tries. */
@ControlPlaneTest
@TestPropertySource(properties = "identity.invite-only=true")
class InviteTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InviteService invites;

    @Autowired
    MutableClock clock;

    private final JsonMapper json = JsonMapper.builder().build();
    private String bearer;

    @BeforeEach
    void newUser() throws Exception {
        // Outlives the tests that move the clock forward.
        bearer = TestUsers.signUpLasting(mvc, Duration.ofHours(4)).bearer();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private String code() {
        return invites.create(1, "test", Optional.empty(), "tester").getFirst();
    }

    private MockHttpServletRequestBuilder activate(String bearer, String code) {
        return post("/v1/me/activate")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}");
    }

    private String body(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        return mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void aFirstSignInIsWaiting() throws Exception {
        mvc.perform(get("/v1/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("waiting"));
    }

    @Test
    void aWaitingUserReachesOnlyTheirAccount() throws Exception {
        var me = body(get("/v1/me").header("Authorization", bearer), 200);
        assertThat(me).contains("waiting");

        mvc.perform(patch("/v1/me")
                        .header("Authorization", bearer)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Asha\"}"))
                .andExpect(status().isOk());

        for (var path : new String[] {"/v1/sessions", "/v1/hosts", "/v1/uploads", "/v1/templates", "/v1/wallet"}) {
            mvc.perform(get(path).header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/problems/invite-required"));
        }
        // The two calls that aren't theirs: other methods on the account path, and other paths under it.
        mvc.perform(post("/v1/hosts")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/invite-required"));
        mvc.perform(get("/v1/me/notifications").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/invite-required"));
    }

    @Test
    void anAcceptedCodeActivatesTheUserAndIsUsedUp() throws Exception {
        var code = code();

        mvc.perform(activate(bearer, code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));

        mvc.perform(get("/v1/sessions").header("Authorization", bearer)).andExpect(status().isOk());
        var used = jdbc.queryForMap(
                "select used_by, used_at from identity.invite_codes where code_hash = ?", InviteCodes.hash(code));
        assertThat(used.get("used_by")).isNotNull();
        assertThat(used.get("used_at")).isNotNull();

        // The same code for someone else fails, and so does it for its own user (already active: a no-op).
        var other = TestUsers.signUp(mvc).bearer();
        mvc.perform(activate(other, code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invite-invalid"));
        mvc.perform(get("/v1/sessions").header("Authorization", other)).andExpect(status().isForbidden());
    }

    @Test
    void theCodeCanBeTypedAnyWay() throws Exception {
        var typed = code().toLowerCase(java.util.Locale.ROOT).replace("-", " ");

        mvc.perform(activate(bearer, typed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void anActiveUserActivatingAgainChangesNothing() throws Exception {
        mvc.perform(activate(bearer, code())).andExpect(status().isOk());
        var spare = code();

        mvc.perform(activate(bearer, spare)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "select used_by is null from identity.invite_codes where code_hash = ?",
                        Boolean.class,
                        InviteCodes.hash(spare)))
                .isTrue();
    }

    @Test
    void wrongUsedAndExpiredCodesAreAnsweredAlike() throws Exception {
        var used = code();
        mvc.perform(activate(TestUsers.signUp(mvc).bearer(), used)).andExpect(status().isOk());
        var expiring = invites.create(1, "short", Optional.of(Duration.ofHours(1)), "tester")
                .getFirst();
        clock.advance(Duration.ofHours(2));

        var wrong = body(activate(bearer, "IDLE-AAAA-AAAA"), 400);
        var reused = body(activate(bearer, used), 400);
        var expired = body(activate(bearer, expiring), 400);

        assertThat(json.readTree(wrong).get("type").asString()).isEqualTo("/problems/invite-invalid");
        assertThat(reused).isEqualTo(wrong);
        assertThat(expired).isEqualTo(wrong);
    }

    @Test
    void aSixthTryInAnHourIsRefusedEvenWithAGoodCode() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(activate(bearer, "IDLE-AAAA-AAA" + i))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/problems/invite-invalid"));
        }
        var good = code();

        mvc.perform(activate(bearer, good))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/too-many-attempts"));
        assertThat(jdbc.queryForObject(
                        "select used_by is null from identity.invite_codes where code_hash = ?",
                        Boolean.class,
                        InviteCodes.hash(good)))
                .isTrue();

        // Another user is not affected, and after the hour this one may try again.
        mvc.perform(activate(TestUsers.signUp(mvc).bearer(), good)).andExpect(status().isOk());
        var second = code();
        clock.advance(Duration.ofMinutes(61));
        mvc.perform(activate(bearer, second)).andExpect(status().isOk());
    }

    @Test
    void codesAreStoredOnlyAsHashes() {
        var code = code();

        var stored = jdbc.queryForObject(
                "select code_hash from identity.invite_codes where code_hash = ?",
                byte[].class,
                InviteCodes.hash(code));
        assertThat(stored).hasSize(32);
        var columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema = 'identity' and table_name = 'invite_codes'",
                String.class);
        assertThat(columns).doesNotContain("code");
        var payload = InviteCodes.normalise(code).substring(4);
        var rows = jdbc.queryForList("select t::text from identity.invite_codes t", String.class);
        assertThat(rows).noneMatch(row -> row.contains(payload) || row.contains(code));
    }

    @Test
    void generatedCodesLookLikeTheExample() {
        assertThat(invites.create(20, "shape", Optional.empty(), "tester"))
                .allMatch(code -> code.matches("IDLE-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"))
                .doesNotHaveDuplicates();
    }

    @Test
    void aNewUserHasNoUseForAnEmptyCode() throws Exception {
        mvc.perform(activate(bearer, "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
    }
}
