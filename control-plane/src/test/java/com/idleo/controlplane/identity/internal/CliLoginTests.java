package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestUsers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Device sign-in for the CLI (ADR 0025): pending, approved, spent once, expired, and the wrong token. */
@ControlPlaneTest
class CliLoginTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private final JsonMapper json = JsonMapper.builder().build();
    private TestUsers.TestUser user;

    @BeforeEach
    void signIn() throws Exception {
        user = TestUsers.signUpLasting(mvc, Duration.ofHours(2));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode start(String device) throws Exception {
        return json.readTree(mvc.perform(post("/v1/cli-logins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\":\"" + device + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private ResultActions poll(JsonNode login) throws Exception {
        return poll(login.get("id").asString(), login.get("pollToken").asString());
    }

    private ResultActions poll(String id, String token) throws Exception {
        return mvc.perform(post("/v1/cli-logins/" + id + "/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pollToken\":\"" + token + "\"}"));
    }

    private ResultActions approve(JsonNode login, String bearer) throws Exception {
        return mvc.perform(post("/v1/cli-logins/" + login.get("id").asString() + "/approve")
                .header("Authorization", bearer));
    }

    @Test
    void startingNeedsNoSignInAndKeepsOnlyHashes() throws Exception {
        var login = start("asha-laptop");
        var code = login.get("userCode").asString();

        assertThat(code).matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}");
        assertThat(login.get("verificationUrl").asString()).isEqualTo("http://localhost:3000/cli-login/?code=" + code);
        assertThat(login.get("intervalSeconds").asLong()).isEqualTo(3);
        assertThat(login.get("expiresInSeconds").asLong()).isEqualTo(600);
        var row = jdbc.queryForObject(
                "select t::text from identity.cli_logins t where id = ?::uuid",
                String.class,
                login.get("id").asString());
        assertThat(row)
                .contains("asha-laptop")
                .doesNotContain(code.replace("-", ""))
                .doesNotContain(login.get("pollToken").asString());
    }

    @Test
    void theCliWaitsUntilABrowserApprovesThenGetsAFullKeyOnce() throws Exception {
        var login = start("asha-laptop");
        poll(login)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/authorization-pending"));

        // The approval page finds it by the code, typed any way.
        var typed = login.get("userCode").asString().toLowerCase(Locale.ROOT).replace("-", "");
        mvc.perform(get("/v1/cli-logins").param("code", typed).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(login.get("id").asString()))
                .andExpect(jsonPath("$.deviceName").value("asha-laptop"));
        approve(login, user.bearer()).andExpect(status().isOk());

        var key = json.readTree(poll(login)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CLI on asha-laptop"))
                .andExpect(jsonPath("$.scope").value("full"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        mvc.perform(get("/v1/me")
                        .header("Authorization", "Bearer " + key.get("key").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id().toString()));
        mvc.perform(get("/v1/api-keys").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.keys[0].createdVia").value("cli-login"));

        // Spent: polling again, or approving again, never hands out a second key.
        poll(login)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/cli-login-expired"));
        approve(login, user.bearer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/cli-login-expired"));
        mvc.perform(get("/v1/cli-logins").param("code", typed).header("Authorization", user.bearer()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.api_keys where user_id = ?", Integer.class, user.id()))
                .isEqualTo(1);
    }

    @Test
    void twoPollsAtOnceGetOneKey() throws Exception {
        var login = start("race");
        approve(login, user.bearer()).andExpect(status().isOk());
        var start = new CountDownLatch(1);
        var calls = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> {
                start.await();
                return poll(login).andReturn().getResponse().getStatus();
            });
        }
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures) {
                statuses.add(future.get());
            }
            assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
            assertThat(statuses).filteredOn(code -> code == 400).hasSize(5);
        }
    }

    @Test
    void theWrongPollTokenOrAnUnknownLoginIsNotFound() throws Exception {
        var login = start("asha-laptop");
        approve(login, user.bearer()).andExpect(status().isOk());

        poll(login.get("id").asString(), "nottherealtoken")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
        poll(UUID.randomUUID().toString(), login.get("pollToken").asString()).andExpect(status().isNotFound());
        mvc.perform(get("/v1/cli-logins").param("code", "BBBB-BBBB").header("Authorization", user.bearer()))
                .andExpect(status().isNotFound());
        // The right one still works afterwards.
        poll(login).andExpect(status().isOk());
    }

    @Test
    void aLoginExpiresAfterTenMinutes() throws Exception {
        var login = start("slow");
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        approve(login, user.bearer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/cli-login-expired"));
        poll(login)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/cli-login-expired"));
        mvc.perform(get("/v1/cli-logins")
                        .param("code", login.get("userCode").asString())
                        .header("Authorization", user.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anApprovedLoginBelongsToWhoeverApprovedIt() throws Exception {
        var login = start("shared");
        approve(login, user.bearer()).andExpect(status().isOk());
        // The same person again changes nothing; someone else can't take it over.
        approve(login, user.bearer()).andExpect(status().isOk());
        approve(login, TestUsers.signUp(mvc).bearer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/cli-login-expired"));

        var key = json.readTree(poll(login)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("key")
                .asString();
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + key))
                .andExpect(jsonPath("$.id").value(user.id().toString()));
    }

    @Test
    void approvingAndFindingNeedASignIn() throws Exception {
        var login = start("anon");
        mvc.perform(post("/v1/cli-logins/" + login.get("id").asString() + "/approve"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/cli-logins").param("code", login.get("userCode").asString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDeviceNeedsAName() throws Exception {
        for (var body : new String[] {"{}", "{\"deviceName\":\"  \"}", "{\"deviceName\":\"" + "x".repeat(61) + "\"}"}) {
            mvc.perform(post("/v1/cli-logins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        }
    }
}
