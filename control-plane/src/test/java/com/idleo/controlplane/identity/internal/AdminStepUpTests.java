package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestPasskey;
import com.idleo.controlplane.TestUsers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Passkeys and step-up (ADR 0051): through the real checks, with a software authenticator. */
@ControlPlaneTest
class AdminStepUpTests {

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

    private MockHttpServletResponse send(TestAdmins.TestAdmin admin, MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(admin.as(request.contentType(MediaType.APPLICATION_JSON)))
                .andReturn()
                .getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    private String reveal(UUID person) {
        return json.writeValueAsString(
                Map.of("field", "email", "id", person.toString(), "reason", "answering a ticket"));
    }

    private void registerPasskey(TestAdmins.TestAdmin admin, TestPasskey passkey, String name) throws Exception {
        var options = body(send(admin, post("/admin/v1/passkeys/options")));
        var made = send(admin, post("/admin/v1/passkeys").content(passkey.register(options, name)));
        assertThat(made.getStatus()).as(made.getContentAsString()).isEqualTo(201);
    }

    private String assertionBody(TestAdmins.TestAdmin admin, TestPasskey passkey) throws Exception {
        var options = body(send(admin, post("/admin/v1/session/passkey-options")));
        var request = new LinkedHashMap<String, Object>();
        request.put("passkey", passkey.assertion(options));
        return json.writeValueAsString(request);
    }

    @Test
    void aSensitiveActionIsRefusedWithoutARecentFactorAndPassesAfterOne() throws Exception {
        var person = TestUsers.signUp(mvc);
        var admin = TestAdmins.signIn(mvc, jdbc, clock);

        // The sign-in was a factor, just now.
        assertThat(send(admin, post("/admin/v1/personal-data/reveal").content(reveal(person.id())))
                        .getStatus())
                .isEqualTo(200);

        clock.advance(Duration.ofMinutes(6));
        var refused = send(admin, post("/admin/v1/personal-data/reveal").content(reveal(person.id())));
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(body(refused).get("type").asString()).isEqualTo("/problems/step-up-required");

        // What isn't sensitive doesn't ask.
        assertThat(send(admin, get("/admin/v1/users")).getStatus()).isEqualTo(200);

        TestAdmins.stepUp(mvc, admin, clock);
        assertThat(send(admin, post("/admin/v1/personal-data/reveal").content(reveal(person.id())))
                        .getStatus())
                .isEqualTo(200);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(send(admin, post("/admin/v1/personal-data/reveal").content(reveal(person.id())))
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void aWrongCodeAtStepUpIsAWrongCode() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var wrong = send(admin, post("/admin/v1/session/step-up").content("{\"totp\":\"000000\"}"));
        assertThat(wrong.getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.admin_code_attempts where user_id = ? and not succeeded",
                        Integer.class,
                        admin.id()))
                .isEqualTo(1);
    }

    @Test
    void anAdminRegistersSeveralNamedPasskeysAndSignsInWithOne() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var phone = new TestPasskey();
        var key = new TestPasskey();
        registerPasskey(admin, phone, "My phone");
        registerPasskey(admin, key, "Security key");
        var listed = body(send(admin, get("/admin/v1/passkeys"))).get("passkeys");
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("name").asString()).isEqualTo("My phone");

        // A new session, from the sign-in page: the role and the ID token alone reach these two calls.
        var options = json.readTree(
                mvc.perform(post("/admin/v1/session/passkey-options").header("Authorization", admin.bearer()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(options.get("rpId").asString()).isEqualTo("localhost");
        assertThat(options.get("allow")).hasSize(2);
        var signIn = mvc.perform(post("/admin/v1/session")
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("passkey", phone.assertion(options)))))
                .andReturn()
                .getResponse();
        assertThat(signIn.getStatus()).as(signIn.getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select last_used_at is not null from identity.admin_passkeys where user_id = ? and name = 'My phone'",
                        Boolean.class,
                        admin.id()))
                .isTrue();
    }

    @Test
    void aPasskeyWithoutUserVerificationIsRefusedAtRegistrationAndAtAssertion() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var unverified = new TestPasskey();
        unverified.verifiesItsUser = false;
        var options = body(send(admin, post("/admin/v1/passkeys/options")));
        var refused = send(admin, post("/admin/v1/passkeys").content(unverified.register(options, "No PIN")));
        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(body(refused).get("type").asString()).isEqualTo("/problems/passkey-not-accepted");

        var passkey = new TestPasskey();
        registerPasskey(admin, passkey, "My phone");
        passkey.verifiesItsUser = false;
        var wrong = send(admin, post("/admin/v1/session/step-up").content(assertionBody(admin, passkey)));
        assertThat(wrong.getStatus()).isEqualTo(400);
    }

    @Test
    void aChallengeWorksOnceAndAnotherAdminsPasskeyIsWrong() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var passkey = new TestPasskey();
        registerPasskey(admin, passkey, "Mine");
        var once = assertionBody(admin, passkey);
        assertThat(send(admin, post("/admin/v1/session/step-up").content(once)).getStatus())
                .isEqualTo(200);
        assertThat(send(admin, post("/admin/v1/session/step-up").content(once)).getStatus())
                .isEqualTo(400);

        var other = TestAdmins.signIn(mvc, jdbc, clock);
        var theirs = new TestPasskey();
        registerPasskey(other, theirs, "Theirs");
        var options = body(send(admin, post("/admin/v1/session/passkey-options")));
        var stolen = json.writeValueAsString(Map.of("passkey", theirs.assertion(options)));
        assertThat(send(admin, post("/admin/v1/session/step-up").content(stolen))
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void changingPasskeysNeedsARecentFactorAndTheStepUpByPasskeyGivesOne() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var passkey = new TestPasskey();
        registerPasskey(admin, passkey, "Mine");
        var id = body(send(admin, get("/admin/v1/passkeys")))
                .get("passkeys")
                .get(0)
                .get("id")
                .asString();

        clock.advance(Duration.ofMinutes(6));
        assertThat(send(admin, post("/admin/v1/passkeys/options")).getStatus()).isEqualTo(403);
        assertThat(send(admin, delete("/admin/v1/passkeys/" + id)).getStatus()).isEqualTo(403);

        assertThat(send(admin, post("/admin/v1/session/step-up").content(assertionBody(admin, passkey)))
                        .getStatus())
                .isEqualTo(200);
        assertThat(send(admin, delete("/admin/v1/passkeys/" + id)).getStatus()).isEqualTo(204);
        assertThat(body(send(admin, get("/admin/v1/passkeys"))).get("passkeys")).isEmpty();
    }

    @Test
    void eachSessionRecordsWhereItStartedAndItsOwnerOrAnOwnerCanEndIt() throws Exception {
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        var support = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var other = TestAdmins.signInAs(mvc, jdbc, clock, "support");

        var mine = body(send(support, get("/admin/v1/me/sessions"))).get("sessions");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("current").asBoolean()).isTrue();
        assertThat(mine.get(0).get("startedAt").isNull()).isFalse();

        // Support sees only their own; ending another's is as if it weren't there.
        assertThat(send(support, get("/admin/v1/admins/sessions")).getStatus()).isEqualTo(403);
        var othersSession = body(send(other, get("/admin/v1/me/sessions")))
                .get("sessions")
                .get(0)
                .get("id")
                .asString();
        assertThat(send(support, post("/admin/v1/me/sessions/" + othersSession + "/end"))
                        .getStatus())
                .isEqualTo(404);

        // The Owner sees everyone's and ends any.
        var everyone = body(send(owner, get("/admin/v1/admins/sessions"))).get("sessions");
        assertThat(everyone.findValuesAsString("id")).contains(othersSession);
        assertThat(send(owner, post("/admin/v1/admins/sessions/" + othersSession + "/end"))
                        .getStatus())
                .isEqualTo(204);
        assertThat(send(other, get("/admin/v1/me/sessions")).getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'admin_session.end' and target_id = ?",
                        Integer.class,
                        othersSession))
                .isEqualTo(1);
    }

    @Test
    void aSignInFromANewAddressOrBrowserTellsTheAdminAndARepeatDoesNot() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        clock.advance(Duration.ofSeconds(30));
        var fromElsewhere = mvc.perform(post("/admin/v1/session")
                        .header("Authorization", admin.bearer())
                        .header("User-Agent", "A new browser")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.20");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totp\":\"" + TestAdmins.code(admin.secret(), clock) + "\"}"))
                .andReturn()
                .getResponse();
        assertThat(fromElsewhere.getStatus()).isEqualTo(200);
        var told = "select count(*) from notifications.notifications where user_id = ? and title like 'A new sign-in%'";
        org.awaitility.Awaitility.await().until(() -> jdbc.queryForObject(told, Integer.class, admin.id()) == 1);
        var body = jdbc.queryForObject(
                "select body from notifications.notifications where user_id = ? and title like 'A new sign-in%'",
                String.class, admin.id());
        assertThat(body).contains("198.51.100.0/24").doesNotContain("198.51.100.20");

        clock.advance(Duration.ofSeconds(30));
        mvc.perform(post("/admin/v1/session")
                .header("Authorization", admin.bearer())
                .header("User-Agent", "A new browser")
                .with(request -> {
                    request.setRemoteAddr("198.51.100.20");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"" + TestAdmins.code(admin.secret(), clock) + "\"}"));
        Thread.sleep(500);
        assertThat(jdbc.queryForObject(told, Integer.class, admin.id())).isEqualTo(1);
    }
}
