package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Every bad token is a 401 problem detail with the RFC 6750 WWW-Authenticate header. */
@ControlPlaneTest
class IdTokenVerificationTests {

    private static final String INVALID_TOKEN = "Bearer error=\"invalid_token\"";

    @Autowired
    MockMvc mvc;

    private final TestIssuers issuers = TestIssuers.get();

    @Test
    void validTokenIsAccepted() throws Exception {
        call("Bearer " + issuers.validToken()).andExpect(status().isOk());
    }

    /** auth_time is optional (ADR 0009); Spring Security 7's claim validator would make it required. */
    @Test
    void aTokenWithoutAuthTimeIsAccepted() throws Exception {
        call("Bearer " + issuers.token(claims -> claims.claim("auth_time", null)))
                .andExpect(status().isOk());
    }

    @Test
    void authTimeInTheFutureIsRejected() throws Exception {
        rejected(call("Bearer "
                + issuers.token(claims ->
                        claims.claim("auth_time", Instant.now().plusSeconds(300).getEpochSecond()))));
    }

    @Test
    void wrongSignatureIsRejected() throws Exception {
        // Issuer A's claims and key id, signed with a key issuer A never published.
        rejected(call("Bearer " + issuers.forgedToken()));
        // Issuer A's claims, signed with an unknown key id.
        rejected(call("Bearer " + issuers.token(issuers.untrustedKey, claims -> {})));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        rejected(call(
                "Bearer " + issuers.token(issuers.untrustedKey, claims -> claims.issuer(issuers.untrustedIssuer))));
    }

    @Test
    void wrongAudienceOnTheSameIssuerIsRejected() throws Exception {
        rejected(call("Bearer " + issuers.token(claims -> claims.audience("some-other-project"))));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        var now = Instant.now();
        rejected(call("Bearer "
                + issuers.token(claims -> claims.issueTime(Date.from(now.minusSeconds(7200)))
                        .expirationTime(Date.from(now.minusSeconds(300))))));
    }

    @Test
    void tokenIssuedInTheFutureBeyondTheSkewIsRejected() throws Exception {
        rejected(call("Bearer "
                + issuers.token(
                        claims -> claims.issueTime(Date.from(Instant.now().plusSeconds(300))))));
    }

    @Test
    void emptySubjectIsRejected() throws Exception {
        rejected(call("Bearer " + issuers.token(claims -> claims.subject(""))));
    }

    @Test
    void missingTokenGets401WithPlainBearerChallenge() throws Exception {
        mvc.perform(get("/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication is required."));
    }

    @Test
    void malformedHeaderIsRejected() throws Exception {
        rejected(call("Bearer not-a-jwt"));
        rejected(call("Bearer two parts"));
    }

    private ResultActions call(String authorization) throws Exception {
        return mvc.perform(get("/v1/me").header("Authorization", authorization));
    }

    private static void rejected(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", INVALID_TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("The bearer token is invalid or has expired."));
    }
}
