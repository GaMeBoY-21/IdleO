package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The security log (ADR 0037): what happened to an account's access, with the address, kept a year and then deleted
 * by its retention rule; shown only in the admin console.
 */
@ControlPlaneTest
class SecurityLogTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RetentionJobs retention;

    @Autowired
    MeterRegistry registry;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private List<String> kinds(UUID userId) {
        return jdbc.queryForList(
                "select kind || coalesce(':' || detail, '') from identity.security_events where user_id = ? order by id",
                String.class,
                userId);
    }

    @Test
    void recordsASignInOncePerSignInWithTheAddress() throws Exception {
        var issuers = TestIssuers.get();
        var subject = UUID.randomUUID().toString();
        var signedIn = Instant.now().minusSeconds(60);
        var email = subject + "@example.com";
        var first = issuers.token(
                claims -> claims.subject(subject).claim("email", email).claim("auth_time", signedIn.getEpochSecond()));
        var again = issuers.token(
                claims -> claims.subject(subject).claim("email", email).claim("auth_time", signedIn.getEpochSecond()));
        var later = issuers.token(claims -> claims.subject(subject)
                .claim("email", email)
                .claim("auth_time", signedIn.plusSeconds(30).getEpochSecond()));
        var id = UUID.fromString(JsonMapper.builder()
                .build()
                .readTree(mvc.perform(get("/v1/me").header("Authorization", "Bearer " + first))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString());
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + again)).andExpect(status().isOk());
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + later)).andExpect(status().isOk());

        assertThat(kinds(id)).containsExactly("sign_in:first", "sign_in");
        assertThat(jdbc.queryForList(
                        "select host(address) from identity.security_events where user_id = ?", String.class, id))
                .containsOnly("127.0.0.1");
    }

    @Test
    void recordsAFailedSignInWithItsAddressAndNoAccount() throws Exception {
        var before = jdbc.queryForObject(
                "select count(*) from identity.security_events where kind = 'sign_in_failed' and user_id is null",
                Integer.class);
        mvc.perform(get("/v1/me")
                        .header("Authorization", "Bearer " + TestIssuers.get().forgedToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/me").header("Authorization", "Bearer idleo_" + "a".repeat(52)))
                .andExpect(status().isUnauthorized());
        var expired = TestIssuers.get()
                .token(claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(600)))
                        .issueTime(Date.from(Instant.now().minusSeconds(4200))));
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForList("""
                        select detail from identity.security_events
                        where kind = 'sign_in_failed' and user_id is null and host(address) = '127.0.0.1'
                        order by id desc limit 3
                        """, String.class)).containsExactlyInAnyOrder("invalid", "api_key", "expired");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.security_events where kind = 'sign_in_failed' and user_id is null",
                        Integer.class))
                .isEqualTo(before + 3);
    }

    @Test
    void recordsKeysMadeAndRevokedAndASuspendedAccountRefused() throws Exception {
        var user = TestUsers.signUp(mvc);
        var made = JsonMapper.builder()
                .build()
                .readTree(mvc.perform(post("/v1/api-keys")
                                .header("Authorization", user.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"laptop\",\"scope\":\"full\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        mvc.perform(delete("/v1/api-keys/" + made.get("id").asString()).header("Authorization", user.bearer()))
                .andExpect(status().isNoContent());
        jdbc.update("update identity.users set status = 'suspended' where id = ?", user.id());
        mvc.perform(get("/v1/me").header("Authorization", user.bearer())).andExpect(status().isForbidden());

        assertThat(kinds(user.id()))
                .containsSubsequence("api_key_created:web", "api_key_revoked", "sign_in_refused:suspended");
    }

    @Test
    void followsTheProvidersNewVerifiedEmailAndRecordsTheChange() throws Exception {
        var subject = UUID.randomUUID().toString();
        var issuers = TestIssuers.get();
        var user = TestUsers.signUp(mvc);
        var first = issuers.token(claims -> claims.subject(subject).claim("email", subject + "@old.example.com"));
        var id = UUID.fromString(JsonMapper.builder()
                .build()
                .readTree(mvc.perform(get("/v1/me").header("Authorization", "Bearer " + first))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString());
        var moved = issuers.token(claims -> claims.subject(subject).claim("email", subject + "@new.example.com"));
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + moved))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(subject + "@new.example.com"));
        var unverified = issuers.token(claims -> claims.subject(subject)
                .claim("email", subject + "@other.example.com")
                .claim("email_verified", false));
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + unverified))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(subject + "@new.example.com"));

        assertThat(kinds(id)).containsOnlyOnce("email_changed");
        assertThat(kinds(user.id())).doesNotContain("email_changed");
    }

    @Test
    void keepsAYearAndThenTheRetentionRuleDeletesIt() {
        var user = UUID.randomUUID();
        var now = clock.instant();
        for (var age : List.of(Duration.ofDays(366), Duration.ofDays(364), Duration.ofDays(1))) {
            jdbc.update(
                    "insert into identity.security_events (id, user_id, kind, address, at)"
                            + " values (?, ?, 'sign_in', '203.0.113.9', ?)",
                    UUID.randomUUID(),
                    user,
                    Timestamp.from(now.minus(age)));
        }
        var rule = retention.rules().get("security-events");

        assertThat(retention.run(rule)).isGreaterThanOrEqualTo(1);
        assertThat(retention.run(rule)).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.security_events where user_id = ?", Integer.class, user))
                .isEqualTo(2);
        assertThat(registry.get("retention.age")
                        .tag("rule", "security-events")
                        .gauge()
                        .value())
                .isLessThan(5);
        assertThat(registry.get("retention.deleted")
                        .tag("rule", "security-events")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void showsAPersonsLogWithAddressesOnlyInTheAdminConsole() throws Exception {
        var person = TestUsers.signUp(mvc);
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(admin.as(get("/admin/v1/users/" + person.id() + "/security-events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].kind").value("sign_in"))
                .andExpect(jsonPath("$.events[0].address").value("127.0.0.1"));
        mvc.perform(get("/admin/v1/users/" + person.id() + "/security-events").header("Authorization", person.bearer()))
                .andExpect(status().isNotFound());
    }
}
