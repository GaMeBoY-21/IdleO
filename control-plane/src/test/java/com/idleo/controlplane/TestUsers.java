package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Fake users for tests in any package: each signs in once, which creates them. */
public final class TestUsers {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestUsers() {}

    /** A signed-in fake user: their bearer header and our user id. */
    public record TestUser(String bearer, UUID id) {}

    public static TestUser signUp(MockMvc mvc) throws Exception {
        return signUp(mvc, TestIssuers.get().validToken());
    }

    /** A user whose verified address is {@code email}, for tests about mail to a particular address. */
    public static TestUser signUpWithEmail(MockMvc mvc, String email) throws Exception {
        return signUp(mvc, TestIssuers.get().token(claims -> claims.claim("email", email)));
    }

    /** A user whose token outlives {@code lifetime}, for tests that move the clock forward. */
    public static TestUser signUpLasting(MockMvc mvc, Duration lifetime) throws Exception {
        var expires = Date.from(Instant.now().plus(lifetime));
        return signUp(mvc, TestIssuers.get().token(claims -> claims.expirationTime(expires)));
    }

    private static TestUser signUp(MockMvc mvc, String token) throws Exception {
        var bearer = "Bearer " + token;
        var body = mvc.perform(get("/v1/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new TestUser(
                bearer, UUID.fromString(JSON.readTree(body).get("id").asString()));
    }
}
