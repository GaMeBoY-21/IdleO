package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@ControlPlaneTest
class HostApiTests {

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

    @Test
    void createNeedsAnIdempotencyKey() throws Exception {
        mvc.perform(post("/v1/hosts")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Laptop\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void createsAPendingHost() throws Exception {
        var bearer = bearer();
        create(bearer, "\"key-1\"", "  Gaming laptop  ")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/v1/hosts/")))
                .andExpect(jsonPath("$.name").value("Gaming laptop"))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist())
                .andExpect(jsonPath("$.gpus").isEmpty());
    }

    @Test
    void aRepeatReturnsTheStoredResponseAndCreatesNothing() throws Exception {
        var bearer = bearer();
        var first = create(bearer, "repeat-me", "Desktop")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse();
        var second = create(bearer, "repeat-me", "Desktop")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse();

        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(second.getHeader("Location")).isEqualTo(first.getHeader("Location"));
        mvc.perform(get("/v1/hosts").header("Authorization", bearer))
                .andExpect(jsonPath("$.hosts.length()").value(1));
    }

    @Test
    void theSameKeyWithADifferentBodyGets422() throws Exception {
        var bearer = bearer();
        create(bearer, "same-key", "First").andExpect(status().isCreated());

        create(bearer, "same-key", "Second")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void aRequestWhileTheFirstIsInFlightGets409AndAnAbandonedOneIsTakenOver() throws Exception {
        var bearer = bearer();
        var userId = userId(bearer);
        var fingerprint = MessageDigest.getInstance("SHA-256")
                .digest("{\"name\":\"In flight\"}".getBytes(StandardCharsets.UTF_8));
        var now = clock.instant();
        jdbc.update(
                """
                insert into hosts.idempotency_keys
                    (user_id, idempotency_key, request_hash, state, locked_at, created_at, expires_at)
                values (?, 'busy', ?, 'in_progress', ?, ?, ?)
                """,
                UUID.fromString(userId),
                fingerprint,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now.plus(Duration.ofHours(24))));

        create(bearer, "busy", "In flight").andExpect(status().isConflict());

        // The first request vanished (say the instance crashed): after a minute the key is taken over.
        jdbc.update(
                "update hosts.idempotency_keys set locked_at = ? where user_id = ? and idempotency_key = 'busy'",
                Timestamp.from(now.minus(Duration.ofMinutes(2))),
                UUID.fromString(userId));
        create(bearer, "busy", "In flight").andExpect(status().isCreated());
    }

    @Test
    void keysAreScopedToTheUserAndExpireAfterADay() throws Exception {
        var alice = bearer();
        var bob = bearer();
        create(alice, "shared-key", "Alice's").andExpect(status().isCreated());
        create(bob, "shared-key", "Bob's").andExpect(status().isCreated());

        clock.advance(Duration.ofHours(25));
        create(tokenAtClock(alice), "shared-key", "Alice's second").andExpect(status().isCreated());
    }

    @Test
    void anotherUsersHostIsA404() throws Exception {
        var owner = bearer();
        var location = create(owner, "k", "Mine")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");
        var stranger = bearer();

        mvc.perform(get(location).header("Authorization", stranger))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post(location + "/enrolment-code").header("Authorization", stranger))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/hosts/" + UUID.randomUUID()).header("Authorization", owner))
                .andExpect(status().isNotFound());
        mvc.perform(get(location).header("Authorization", owner)).andExpect(status().isOk());
    }

    @Test
    void listsOnlyMyHosts() throws Exception {
        var mine = bearer();
        create(mine, "a", "One").andExpect(status().isCreated());
        create(mine, "b", "Two").andExpect(status().isCreated());
        create(bearer(), "a", "Someone else's").andExpect(status().isCreated());

        mvc.perform(get("/v1/hosts").header("Authorization", mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hosts.length()").value(2))
                .andExpect(jsonPath("$.hosts[0].name").value("One"))
                .andExpect(jsonPath("$.hosts[1].name").value("Two"));
    }

    @Test
    void anInvalidNameGets400NamingTheField() throws Exception {
        create(bearer(), "k", " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void anEnrolmentCodeIsShownOnceAndNeverCached() throws Exception {
        var bearer = bearer();
        var location = create(bearer, "k", "Laptop").andReturn().getResponse().getHeader("Location");

        mvc.perform(post(location + "/enrolment-code").header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code")
                        .value(org.hamcrest.Matchers.matchesPattern(
                                "[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}")))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    private ResultActions create(String bearer, String idempotencyKey, String name) throws Exception {
        return mvc.perform(post("/v1/hosts")
                .header("Authorization", bearer)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("name", name))));
    }

    private String userId(String bearer) throws Exception {
        var body = mvc.perform(get("/v1/me").header("Authorization", bearer))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    /** A new person, signed in, who has accepted the Terms as a person does at their first sign-in. */
    private String bearer() throws Exception {
        return TestUsers.signUp(mvc).bearer();
    }

    /** The same user's token, re-issued at the test clock's time (for tests that move the clock). */
    private String tokenAtClock(String bearer) {
        var subject = subjectOf(bearer);
        var now = clock.instant();
        return "Bearer "
                + TestIssuers.get()
                        .token(claims -> claims.subject(subject)
                                .issueTime(Date.from(now))
                                .expirationTime(Date.from(now.plusSeconds(3600)))
                                .claim("auth_time", now.getEpochSecond()));
    }

    private static String subjectOf(String bearer) {
        try {
            return com.nimbusds.jwt.SignedJWT.parse(bearer.substring("Bearer ".length()))
                    .getJWTClaimsSet()
                    .getSubject();
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }
}
