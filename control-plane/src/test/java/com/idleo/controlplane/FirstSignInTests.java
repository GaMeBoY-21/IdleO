package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@ControlPlaneTest
class FirstSignInTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private final TestIssuers issuers = TestIssuers.get();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void firstSignInCreatesExactlyOneUser() throws Exception {
        var subject = UUID.randomUUID().toString();
        var token = issuers.token(claims -> claims.subject(subject).claim("email", "renter@example.com"));

        long started = System.nanoTime();
        var body = mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.email").value("renter@example.com"))
                .andExpect(jsonPath("$.displayName").value("Demo Renter"))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("first-sign-in: " + elapsedMillis + " ms; GET /v1/me -> " + body);

        var userId = json.readTree(body).get("id").asString();
        assertThat(UUID.fromString(userId).version()).isEqualTo(7);
        assertThat(usersFor(issuers.issuerA, subject)).containsExactly(userId);

        // A later request finds the same user and creates nothing.
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId));
        assertThat(usersFor(issuers.issuerA, subject)).containsExactly(userId);
    }

    @Test
    void concurrentFirstRequestsMakeOneUser() throws Exception {
        var subject = UUID.randomUUID().toString();
        var email = "concurrent-" + subject + "@example.com";
        var token = issuers.token(claims -> claims.subject(subject).claim("email", email));
        int requests = 12;
        var start = new CountDownLatch(1);
        var calls = new ArrayList<Callable<String>>();
        for (int i = 0; i < requests; i++) {
            calls.add(() -> {
                start.await();
                return mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
            });
        }

        var ids = new ArrayList<String>();
        try (var pool = Executors.newFixedThreadPool(requests)) {
            var futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            for (var future : futures) {
                ids.add(json.readTree(future.get()).get("id").asString());
            }
        }

        assertThat(ids).hasSize(requests).containsOnly(ids.getFirst());
        assertThat(usersFor(issuers.issuerA, subject)).containsExactly(ids.getFirst());
        assertThat(jdbc.queryForObject("select count(*) from identity.users where email = ?", Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void unverifiedEmailGets403AndNoAccount() throws Exception {
        var subject = UUID.randomUUID().toString();
        var token = issuers.token(claims -> claims.subject(subject).claim("email_verified", false));

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail")
                        .value("Your email address must be verified with the sign-in provider before you can use"
                                + " this account."));

        assertThat(usersFor(issuers.issuerA, subject)).isEmpty();
    }

    @Test
    void bothTrustedIssuersWorkAndEachIdentityGetsItsOwnRow() throws Exception {
        // The provider move (ADR 0009): two issuers trusted at once. The same subject and email at
        // two issuers are two identities, and are never linked automatically.
        var subject = UUID.randomUUID().toString();
        var email = "moving-" + subject + "@example.com";
        var fromA = issuers.token(claims -> claims.subject(subject).claim("email", email));
        var fromB = issuers.token(
                issuers.keyB,
                claims -> claims.issuer(issuers.issuerB)
                        .audience(TestIssuers.AUDIENCE_B)
                        .subject(subject)
                        .claim("email", email));

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + fromA)).andExpect(status().isOk());
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + fromB)).andExpect(status().isOk());

        var userA = usersFor(issuers.issuerA, subject);
        var userB = usersFor(issuers.issuerB, subject);
        assertThat(userA).hasSize(1);
        assertThat(userB).hasSize(1);
        assertThat(userA).doesNotContainAnyElementsOf(userB);
    }

    private java.util.List<String> usersFor(String issuer, String subject) {
        return jdbc.queryForList(
                "select user_id::text from identity.external_identities where issuer = ? and subject = ?",
                String.class,
                issuer,
                subject);
    }
}
