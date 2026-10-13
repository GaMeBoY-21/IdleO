package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.SecretScan;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.AuditTrail;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who reaches the admin console (ADR 0027): an admin, with an ID token, a second factor and a live admin session.
 * Everyone and everything else gets the 404 of a path that doesn't exist.
 */
@ControlPlaneTest
class AdminAccessTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    AdminRoles roles;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;

    @Autowired
    com.idleo.controlplane.IdGenerator ids;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder code(
            MockHttpServletRequestBuilder request, String field, String code) {
        return request.contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":\"" + code + "\"}");
    }

    /** The 404 a path that doesn't exist gives an admin who may see everything. */
    private JsonNode realNotFound(String path) throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        return body(mvc.perform(admin.as(get(path))).andExpect(status().isNotFound()));
    }

    @Test
    void normalUsersAnonymousCallersAndApiKeysGetTheSameNotFoundAsAMissingPath() throws Exception {
        var user = TestUsers.signUp(mvc);
        var missing = realNotFound("/admin/v1/no-such-thing");
        assertThat(body(mvc.perform(get("/admin/v1/no-such-thing").header("Authorization", user.bearer()))
                        .andExpect(status().isNotFound())))
                .isEqualTo(missing);
        assertThat(body(mvc.perform(get("/admin/v1/no-such-thing")).andExpect(status().isNotFound())))
                .isEqualTo(missing);
        // A real admin path looks the same as a missing one, but for the address it names.
        var withoutAddress = ((tools.jackson.databind.node.ObjectNode) missing.deepCopy()).without("instance");
        for (var path : new String[] {"/admin/v1/audit", "/admin/v1/session"}) {
            for (var request : new MockHttpServletRequestBuilder[] {
                get(path).header("Authorization", user.bearer()),
                get(path),
                get(path).header("Authorization", "Bearer not-a-token")
            }) {
                var found = (tools.jackson.databind.node.ObjectNode)
                        body(mvc.perform(request).andExpect(status().isNotFound()));
                assertThat(found.get("instance").asString()).isEqualTo(path);
                assertThat(found.without("instance")).isEqualTo(withoutAddress);
            }
        }
        mvc.perform(post("/admin/v1/second-factor").header("Authorization", user.bearer()))
                .andExpect(status().isNotFound());

        // An admin's own API key, even with their live admin session beside it.
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var key = body(mvc.perform(post("/v1/api-keys")
                                .header("Authorization", admin.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"laptop\",\"scope\":\"full\"}"))
                        .andExpect(status().isCreated()))
                .get("key")
                .asString();
        mvc.perform(get("/admin/v1/audit")
                        .header("Authorization", "Bearer " + key)
                        .header("Admin-Session", admin.session()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/v1/session").header("Authorization", "Bearer " + key))
                .andExpect(status().isNotFound());
        mvc.perform(admin.as(get("/admin/v1/audit"))).andExpect(status().isOk());
    }

    @Test
    void theRoleAloneOpensOnlyTheSecondFactorUntilASessionStarts() throws Exception {
        var user = TestUsers.signUp(mvc);
        TestAdmins.grant(jdbc, user.id());
        mvc.perform(get("/admin/v1/audit").header("Authorization", user.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/v1/session").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.active").value(false));
        // Without a second factor, no code starts a session.
        mvc.perform(code(post("/admin/v1/session").header("Authorization", user.bearer()), "totp", "123456"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/second-factor-missing"));

        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(get("/admin/v1/audit").header("Authorization", admin.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/v1/audit")
                        .header("Authorization", admin.bearer())
                        .header("Admin-Session", "made-up"))
                .andExpect(status().isNotFound());
        mvc.perform(admin.as(get("/admin/v1/audit"))).andExpect(status().isOk());
    }

    @Test
    void enrolmentShowsTheSecretOnceConfirmsItWithACodeAndKeepsNothingInTheClear() throws Exception {
        var user = TestUsers.signUp(mvc);
        TestAdmins.grant(jdbc, user.id());
        var first = body(mvc.perform(post("/admin/v1/second-factor").header("Authorization", user.bearer()))
                .andExpect(status().isCreated()));
        var second = body(mvc.perform(post("/admin/v1/second-factor").header("Authorization", user.bearer()))
                .andExpect(status().isCreated()));
        assertThat(second.get("secret").asString())
                .hasSize(32)
                .matches("[A-Z2-7]+")
                .isNotEqualTo(first.get("secret").asString());
        assertThat(second.get("otpauthUri").asString())
                .startsWith("otpauth://totp/IdleO:")
                .contains("secret=" + second.get("secret").asString(), "issuer=IdleO", "digits=6", "period=30");

        // The replaced secret's code is wrong; the newest one's confirms.
        var replaced = TestAdmins.base32(first.get("secret").asString());
        var secret = TestAdmins.base32(second.get("secret").asString());
        mvc.perform(code(
                        post("/admin/v1/second-factor/confirm").header("Authorization", user.bearer()),
                        "totp",
                        TestAdmins.code(replaced, clock)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/admin-code-invalid"));
        var confirmed = body(mvc.perform(code(
                        post("/admin/v1/second-factor/confirm").header("Authorization", user.bearer()),
                        "totp",
                        TestAdmins.code(secret, clock)))
                .andExpect(status().isOk()));
        var codes = new HashSet<String>();
        confirmed.get("recoveryCodes").forEach(code -> codes.add(code.asString()));
        assertThat(codes).hasSize(10).allMatch(code -> code.matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}"));
        mvc.perform(get("/admin/v1/session")
                        .header("Authorization", user.bearer())
                        .header("Admin-Session", confirmed.get("token").asString()))
                .andExpect(jsonPath("$.enrolled").value(true))
                .andExpect(jsonPath("$.active").value(true));

        // Never shown again.
        mvc.perform(post("/admin/v1/second-factor").header("Authorization", user.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/second-factor-exists"));
        var secrets = new java.util.ArrayList<String>(codes);
        secrets.add(second.get("secret").asString());
        secrets.add(confirmed.get("token").asString());
        assertThat(SecretScan.tablesContaining(jdbc, secrets.toArray(String[]::new)))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'admin.second_factor_confirm'"
                                + " and target_id = ?",
                        Integer.class,
                        user.id().toString()))
                .isOne();
    }

    @Test
    void aCodeStartsASessionOnceAndARecoveryCodeWorksOnce() throws Exception {
        var user = TestUsers.signUpLasting(mvc, Duration.ofHours(2));
        TestAdmins.grant(jdbc, user.id());
        var secret = TestAdmins.base32(
                body(mvc.perform(post("/admin/v1/second-factor").header("Authorization", user.bearer())))
                        .get("secret")
                        .asString());
        var confirmed = body(mvc.perform(code(
                post("/admin/v1/second-factor/confirm").header("Authorization", user.bearer()),
                "totp",
                TestAdmins.code(secret, clock))));
        var recovery = confirmed.get("recoveryCodes").get(3).asString();

        // The code that confirmed can't start a session too; the next one can, once.
        mvc.perform(code(
                        post("/admin/v1/session").header("Authorization", user.bearer()),
                        "totp",
                        TestAdmins.code(secret, clock)))
                .andExpect(status().isBadRequest());
        clock.advance(Duration.ofSeconds(30));
        var next = TestAdmins.code(secret, clock);
        var started =
                body(mvc.perform(code(post("/admin/v1/session").header("Authorization", user.bearer()), "totp", next))
                        .andExpect(status().isOk()));
        assertThat(started.get("expiresAt").asString()).isNotBlank();
        mvc.perform(get("/admin/v1/audit")
                        .header("Authorization", user.bearer())
                        .header("Admin-Session", started.get("token").asString()))
                .andExpect(status().isOk());
        mvc.perform(code(post("/admin/v1/session").header("Authorization", user.bearer()), "totp", next))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/admin-code-invalid"));

        // A recovery code, typed in lower case without its dash, works once.
        var typed = recovery.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        mvc.perform(code(post("/admin/v1/session").header("Authorization", user.bearer()), "recoveryCode", typed))
                .andExpect(status().isOk());
        mvc.perform(code(post("/admin/v1/session").header("Authorization", user.bearer()), "recoveryCode", recovery))
                .andExpect(status().isBadRequest());
        // Both or neither isn't a request.
        mvc.perform(post("/admin/v1/session")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
    }

    @Test
    void theFifthWrongCodeInFifteenMinutesLocksAdminAccessUntilItIsGrantedAgain() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var email = jdbc.queryForObject("select email from identity.users where id = ?", String.class, admin.id());
        // Four wrong, then a quarter of an hour: they no longer count.
        for (int i = 0; i < 4; i++) {
            mvc.perform(code(post("/admin/v1/session").header("Authorization", admin.bearer()), "totp", "000000"))
                    .andExpect(status().isBadRequest());
        }
        clock.advance(Duration.ofMinutes(16));
        for (int i = 0; i < 4; i++) {
            mvc.perform(code(
                            post("/admin/v1/session").header("Authorization", admin.bearer()),
                            "recoveryCode",
                            "AAAAA-AAAAA"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("That code isn't right, or was already used."));
        }
        var session = TestAdmins.signInAgain(mvc, admin, clock);
        mvc.perform(code(post("/admin/v1/session").header("Authorization", admin.bearer()), "totp", "999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("now locked")));

        // Locked: the console is gone, even the second factor and the session that was live.
        mvc.perform(get("/admin/v1/session").header("Authorization", admin.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/v1/audit")
                        .header("Authorization", admin.bearer())
                        .header("Admin-Session", session))
                .andExpect(status().isNotFound());
        clock.advance(Duration.ofSeconds(30));
        mvc.perform(code(
                        post("/admin/v1/session").header("Authorization", admin.bearer()),
                        "totp",
                        TestAdmins.code(admin.secret(), clock)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'admin.lock' and target_id = ?",
                        Integer.class,
                        admin.id().toString()))
                .isOne();

        // Revoked and granted again: a fresh enrolment.
        var operator = AuditTrail.Actor.operator("tester");
        assertThat(roles.revoke(email, "locked out by wrong codes", operator).changed())
                .isTrue();
        assertThat(roles.grant(email, "back after a lockout", operator).changed())
                .isTrue();
        mvc.perform(get("/admin/v1/session").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.enrolled").value(false));
        mvc.perform(post("/admin/v1/second-factor").header("Authorization", admin.bearer()))
                .andExpect(status().isCreated());
    }

    @Test
    void anAdminSessionLastsThirtyMinutesFromItsLastUse() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        clock.advance(Duration.ofMinutes(29));
        mvc.perform(admin.as(get("/admin/v1/audit"))).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(29));
        mvc.perform(admin.as(get("/admin/v1/audit"))).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(31));
        mvc.perform(admin.as(get("/admin/v1/audit"))).andExpect(status().isNotFound());
        mvc.perform(admin.as(get("/admin/v1/session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void usedEveryTwentyFiveMinutesASessionStillEndsEightHoursAfterItBegan() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var identity = jdbc.queryForObject(
                "select identity_id from identity.admin_sessions where user_id = ?", UUID.class, admin.id());
        // Its own clock, so eight hours pass for this session alone.
        var own = new MutableClock();
        var sessions = new AdminSessions(
                org.springframework.jdbc.core.simple.JdbcClient.create(jdbc),
                ids,
                own,
                new AdminProperties("unused", Duration.ofMinutes(30), Duration.ofHours(8), "IdleO"));
        var issued = transactions.execute(status -> sessions.start(admin.id(), identity));
        for (int i = 0; i < 19; i++) {
            own.advance(Duration.ofMinutes(25));
            assertThat(sessions.use(admin.id(), identity, issued.token())).isPresent();
        }
        assertThat(sessions.use(admin.id(), identity, issued.token()).orElseThrow())
                .isEqualTo(issued.expiresAt().minus(Duration.ofMinutes(30)).plus(Duration.ofHours(8)));
        own.advance(Duration.ofMinutes(25));
        assertThat(sessions.use(admin.id(), identity, issued.token())).isEmpty();
    }

    @Test
    void aSessionWorksOnlyWithTheSignInThatMadeItAndEndsWhenAsked() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var other = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(get("/admin/v1/audit")
                        .header("Authorization", other.bearer())
                        .header("Admin-Session", admin.session()))
                .andExpect(status().isNotFound());
        mvc.perform(admin.as(delete("/admin/v1/session"))).andExpect(status().isNoContent());
        mvc.perform(admin.as(get("/admin/v1/audit"))).andExpect(status().isNotFound());
        mvc.perform(other.as(get("/admin/v1/audit"))).andExpect(status().isOk());
    }
}
