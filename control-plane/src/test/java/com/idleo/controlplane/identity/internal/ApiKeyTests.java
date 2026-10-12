package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestUsers;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API keys (ADR 0025): shown once, stored as a hash, scoped, refused where a browser is needed, revocable. */
@ControlPlaneTest
class ApiKeyTests {

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

    private JsonNode create(String name, String scope) throws Exception {
        var body = mvc.perform(post("/v1/api-keys")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"scope\":\"" + scope + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String key) {
        return request.header("Authorization", "Bearer " + key);
    }

    @Test
    void aKeyIsShownOnceAndStoredOnlyAsItsHash() throws Exception {
        var created = create("laptop", "full");
        var key = created.get("key").asString();

        assertThat(key).matches("idleo_[a-z2-7]{52}");
        assertThat(created.get("prefix").asString()).isEqualTo(key.substring(0, 14));
        var stored = jdbc.queryForObject(
                "select key_hash from identity.api_keys where id = ?::uuid",
                byte[].class,
                created.get("id").asString());
        assertThat(stored).isEqualTo(ApiKeySecrets.hash(key));
        var rows = String.join(" ", jdbc.queryForList("select t::text from identity.api_keys t", String.class));
        assertThat(rows).doesNotContain(key.substring(14));
        var replays = String.join(
                " ",
                jdbc.queryForList("select coalesce(response_body, '') from identity.idempotency_keys", String.class));
        assertThat(replays).doesNotContain(key.substring(14));

        mvc.perform(get("/v1/api-keys").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].name").value("laptop"))
                .andExpect(jsonPath("$.keys[0].prefix").value(key.substring(0, 14)))
                .andExpect(jsonPath("$.keys[0].key").doesNotExist());
    }

    @Test
    void aKeyActsForItsOwner() throws Exception {
        var key = create("script", "full").get("key").asString();

        mvc.perform(as(get("/v1/me"), key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id().toString()))
                .andExpect(jsonPath("$.signInMethods").isEmpty());
        mvc.perform(as(get("/v1/sessions"), key)).andExpect(status().isOk());
    }

    @Test
    void anUnknownKeyIsUnauthenticated() throws Exception {
        mvc.perform(as(get("/v1/me"), "idleo_" + "a".repeat(52)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/unauthenticated"));
    }

    @Test
    void aReadKeyOnlyReadsButMayRevokeItself() throws Exception {
        var created = create("dashboard", "read");
        var key = created.get("key").asString();

        mvc.perform(as(get("/v1/wallet"), key)).andExpect(status().isOk());
        mvc.perform(as(get("/v1/api-keys"), key)).andExpect(status().isOk());
        mvc.perform(as(patch("/v1/me"), key)
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/api-key-read-only"));
        var other = create("other", "full").get("id").asString();
        mvc.perform(as(delete("/v1/api-keys/" + other), key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/api-key-read-only"));

        mvc.perform(as(delete("/v1/api-keys/" + created.get("id").asString()), key))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/v1/wallet"), key)).andExpect(status().isUnauthorized());
    }

    @Test
    void noKeyCanDoWhatNeedsABrowser() throws Exception {
        var key = create("ci", "full").get("key").asString();
        var host = json.readTree(mvc.perform(post("/v1/hosts")
                                .header("Authorization", user.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Laptop\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        var login = UUID.randomUUID();

        for (var request : new MockHttpServletRequestBuilder[] {
            post("/v1/api-keys")
                    .header("Idempotency-Key", "k")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"x\",\"scope\":\"full\"}"),
            get("/v1/cli-logins").param("code", "WDJB-MJHT"),
            post("/v1/cli-logins/" + login + "/approve"),
            post("/v1/hosts")
                    .header("Idempotency-Key", "h")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"x\"}"),
            delete("/v1/hosts/" + host),
            put("/v1/hosts/" + host + "/listing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            post("/v1/hosts/" + host + "/enrolment-code"),
            put("/v1/me/notifications").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"),
        }) {
            mvc.perform(as(request, key))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/problems/browser-sign-in-required"));
        }
        // Everything else a renter or an owner does is open to a full key: their machine, by name, among them.
        mvc.perform(as(get("/v1/hosts/" + host), key)).andExpect(status().isOk());
        mvc.perform(as(get("/v1/me/notifications"), key)).andExpect(status().isOk());
    }

    @Test
    void aFullKeyMayRevokeAnyOfItsOwnersKeysButNoOneElses() throws Exception {
        var key = create("cli", "full").get("key").asString();
        var other = create("old laptop", "read");

        mvc.perform(as(delete("/v1/api-keys/" + other.get("id").asString()), key))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/v1/me"), other.get("key").asString())).andExpect(status().isUnauthorized());
        // Again: nothing changes.
        mvc.perform(as(delete("/v1/api-keys/" + other.get("id").asString()), key))
                .andExpect(status().isNoContent());

        var stranger = TestUsers.signUp(mvc);
        var theirs = json.readTree(mvc.perform(post("/v1/api-keys")
                                .header("Authorization", stranger.bearer())
                                .header("Idempotency-Key", "theirs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"theirs\",\"scope\":\"full\"}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        mvc.perform(as(delete("/v1/api-keys/" + theirs), key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
        mvc.perform(get("/v1/api-keys").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.keys.length()").value(1));
    }

    @Test
    void lastUsedIsWrittenAtMostOnceAMinute() throws Exception {
        var created = create("poller", "read");
        var key = created.get("key").asString();
        var id = created.get("id").asString();
        var lastUsed = (java.util.function.Supplier<Object>) () ->
                jdbc.queryForObject("select last_used_at from identity.api_keys where id = ?::uuid", Object.class, id);
        assertThat(lastUsed.get()).isNull();

        mvc.perform(as(get("/v1/me"), key)).andExpect(status().isOk());
        var first = lastUsed.get();
        clock.advance(Duration.ofSeconds(30));
        mvc.perform(as(get("/v1/me"), key)).andExpect(status().isOk());
        assertThat(lastUsed.get()).isEqualTo(first);
        clock.advance(Duration.ofSeconds(31));
        mvc.perform(as(get("/v1/me"), key)).andExpect(status().isOk());
        assertThat(lastUsed.get()).isNotEqualTo(first);
    }

    @Test
    void aPersonHasAtMostTwentyKeys() throws Exception {
        for (int i = 0; i < ApiKeyStore.MAX_ACTIVE; i++) {
            create("key " + i, "read");
        }
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", "one-too-many")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"one too many\",\"scope\":\"read\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/too-many-api-keys"));
    }

    @Test
    void aRepeatedCreateNamesTheKeyButNeverShowsItAgain() throws Exception {
        var request = post("/v1/api-keys")
                .header("Authorization", user.bearer())
                .header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"twice\",\"scope\":\"full\"}");
        var first = json.readTree(mvc.perform(request)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

        var again = mvc.perform(request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/secret-shown-once"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(again)
                .contains(first.get("id").asString())
                .doesNotContain(first.get("key").asString());
        mvc.perform(get("/v1/api-keys").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.keys.length()").value(1));
    }

    @Test
    void aKeyIsHeldToItsOwnersStatus() throws Exception {
        var key = create("held", "full").get("key").asString();

        jdbc.update("update identity.users set status = 'waiting' where id = ?", user.id());
        mvc.perform(as(get("/v1/me"), key)).andExpect(status().isOk());
        mvc.perform(as(get("/v1/sessions"), key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/invite-required"));

        jdbc.update("update identity.users set status = 'suspended' where id = ?", user.id());
        mvc.perform(as(get("/v1/me"), key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/account-suspended"));
    }

    @Test
    void aKeyNeedsANameAndAScope() throws Exception {
        for (var body : new String[] {
            "{\"name\":\" \",\"scope\":\"full\"}",
            "{\"name\":\"x\",\"scope\":\"admin\"}",
            "{\"name\":\"" + "x".repeat(61) + "\",\"scope\":\"full\"}",
            "{\"name\":\"x\"}"
        }) {
            mvc.perform(post("/v1/api-keys")
                            .header("Authorization", user.bearer())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        }
    }
}
