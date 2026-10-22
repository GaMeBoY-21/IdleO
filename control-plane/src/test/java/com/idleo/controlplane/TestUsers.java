package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.http.MediaType;
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

    /** A user with this address whose token outlives {@code lifetime}. */
    public static TestUser signUpWithEmailLasting(MockMvc mvc, String email, Duration lifetime) throws Exception {
        var expires = Date.from(Instant.now().plus(lifetime));
        return signUp(
                mvc,
                TestIssuers.get().token(claims -> claims.claim("email", email).expirationTime(expires)));
    }

    /** A user signed in as {@code subject} at the provider, whose token outlives {@code lifetime}. */
    public static TestUser signUpAs(MockMvc mvc, String subject, String email, Duration lifetime) throws Exception {
        return signUp(mvc, bearer(subject, email, Instant.now(), lifetime).substring("Bearer ".length()));
    }

    /**
     * A bearer header for the person signed in as {@code subject}, as if they signed in at the provider at {@code
     * signedIn} (the token's {@code auth_time}), for tests of what needs a recent sign-in.
     */
    public static String bearer(String subject, String email, Instant signedIn, Duration lifetime) {
        var expires = Date.from(Instant.now().plus(lifetime));
        return "Bearer "
                + TestIssuers.get()
                        .token(claims -> claims.subject(subject)
                                .claim("email", email)
                                .claim("auth_time", signedIn.getEpochSecond())
                                .expirationTime(expires));
    }

    /**
     * A user who hasn't accepted the Terms and the privacy notice, for tests of what that means (ADR 0038). Every other user
     * here has, as a person does at their first sign-in: the API refuses to create anything for one who hasn't.
     */
    public static TestUser signUpWithoutTerms(MockMvc mvc) throws Exception {
        return signUp(mvc, TestIssuers.get().validToken(), false);
    }

    private static TestUser signUp(MockMvc mvc, String token) throws Exception {
        return signUp(mvc, token, true);
    }

    private static TestUser signUp(MockMvc mvc, String token, boolean acceptTerms) throws Exception {
        var bearer = "Bearer " + token;
        var body = mvc.perform(get("/v1/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var me = JSON.readTree(body);
        if (acceptTerms && !me.get("notices").get("accepted").asBoolean()) {
            mvc.perform(post("/v1/me/notices")
                            .header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"termsVersion\":\"%s\",\"privacyVersion\":\"%s\",\"ageConfirmed\":true}"
                                    .formatted(
                                            me.get("notices")
                                                    .get("termsVersion")
                                                    .asString(),
                                            me.get("notices")
                                                    .get("privacyVersion")
                                                    .asString())))
                    .andExpect(status().isOk());
        }
        return new TestUser(bearer, UUID.fromString(me.get("id").asString()));
    }
}
