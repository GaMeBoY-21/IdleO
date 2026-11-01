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
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.OpenToWaitingUsers;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Access by invitation (ADR 0024): waiting users, codes, and the limit on tries. */
@ControlPlaneTest
@TestPropertySource(properties = "identity.invite-only=true")
@DirtiesContext
class InviteTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InviteService invites;

    @Autowired
    MutableClock clock;

    @Autowired
    CodeHashes hashes;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping routes;

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
        return invites.create(1, "test", Optional.empty(), AuditTrail.Actor.operator("tester"))
                .codes()
                .getFirst();
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
    void aWaitingUsersFewCallsAreDecidedByRouteAndFailClosed() throws Exception {
        var open = new java.util.TreeSet<String>();
        routes.getHandlerMethods().forEach((info, handler) -> {
            if (handler.hasMethodAnnotation(OpenToWaitingUsers.class)) {
                info.getMethodsCondition()
                        .getMethods()
                        .forEach(method -> info.getPathPatternsCondition()
                                .getPatternValues()
                                .forEach(pattern -> open.add(method.name() + " " + pattern)));
            }
        });
        assertThat(open)
                .containsExactlyInAnyOrder(
                        "GET /v1/me",
                        "PATCH /v1/me",
                        "POST /v1/me/activate",
                        // The Terms, and the person's own data (ADR 0037): theirs whether or not they have a code.
                        "POST /v1/me/notices",
                        "GET /v1/me/data",
                        "GET /v1/me/exports",
                        "POST /v1/me/exports",
                        "GET /v1/me/exports/{id}/download",
                        "GET /v1/me/deletion",
                        "POST /v1/me/deletion",
                        "POST /v1/me/deletion/confirm",
                        "DELETE /v1/me/deletion",
                        // Asking for help, and reading the answers (ADR 0037).
                        "POST /v1/support/tickets",
                        "GET /v1/support/tickets",
                        "GET /v1/support/tickets/{id}",
                        "POST /v1/support/tickets/{id}/messages");

        // A spelling that routes to their own account is their own account; one that routes elsewhere is not.
        mvc.perform(get(java.net.URI.create("/v1/m%65")).header("Authorization", bearer))
                .andExpect(status().isOk());
        for (var path : new String[] {"/v1/w%61llet", "/v1/me/notific%61tions", "/v1/%6De/notifications"}) {
            mvc.perform(get(java.net.URI.create(path)).header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/problems/invite-required"));
        }
        // A request that reaches no handler at all is outside the few calls too.
        mvc.perform(get("/v1/no-such-call").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/invite-required"));
    }

    @Test
    void aCodeIsStoredKeyedSoAPlainHashFromBeforeNeverWorks() throws Exception {
        var code = code();
        var plain = java.security.MessageDigest.getInstance("SHA-256")
                .digest(InviteCodes.normalise(code).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.invite_codes where code_hash = ?", Integer.class, plain))
                .isZero();

        // A code stored the old way, with its plain hash, is a code nobody can spend.
        var old = "IDLE-OLDP-LAIN";
        jdbc.update(
                "insert into identity.invite_codes (id, code_hash, note, created_by, created_at) values"
                        + " (gen_random_uuid(), ?, 'before keyed hashes', 'tester', now())",
                (Object) java.security.MessageDigest.getInstance("SHA-256")
                        .digest(InviteCodes.normalise(old).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        mvc.perform(activate(bearer, old))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/invite-invalid"));
    }

    @Test
    void anAcceptedCodeActivatesTheUserAndIsUsedUp() throws Exception {
        var code = code();

        mvc.perform(activate(bearer, code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));

        mvc.perform(get("/v1/sessions").header("Authorization", bearer)).andExpect(status().isOk());
        var used = jdbc.queryForMap(
                "select used_by, used_at from identity.invite_codes where code_hash = ?",
                hashes.hash(CodeHashes.Kind.INVITE, code));
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
                        hashes.hash(CodeHashes.Kind.INVITE, spare)))
                .isTrue();
    }

    @Test
    void wrongUsedAndExpiredCodesAreAnsweredAlike() throws Exception {
        var used = code();
        mvc.perform(activate(TestUsers.signUp(mvc).bearer(), used)).andExpect(status().isOk());
        var expiring = invites.create(1, "short", Optional.of(Duration.ofHours(1)), AuditTrail.Actor.operator("tester"))
                .codes()
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
                        hashes.hash(CodeHashes.Kind.INVITE, good)))
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
                hashes.hash(CodeHashes.Kind.INVITE, code));
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
        assertThat(invites.create(20, "shape", Optional.empty(), AuditTrail.Actor.operator("tester"))
                        .codes())
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
