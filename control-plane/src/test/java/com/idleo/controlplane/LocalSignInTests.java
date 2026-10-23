package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The development sign-in server's tokens pass the same checks as Identity Platform's (ADR 0013), and the
 * development settings this profile uses are exactly what the prod guard refuses.
 */
@ControlPlaneTest
class LocalSignInTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    LocalSignInContainer localSignIn;

    @Autowired
    Environment environment;

    @Autowired
    MutableClock clock;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Its tokens carry the real time; other tests move the shared clock. */
    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    private String token(String clientId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(localSignIn.issuer() + "/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&client_id=" + clientId + "&client_secret=unused&scope=openid"
                                + "&auth_time=" + clock.instant().getEpochSecond()))
                .build();
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return JsonMapper.builder()
                    .build()
                    .readTree(response.body())
                    .get("access_token")
                    .asString();
        }
    }

    @Test
    void aDevelopmentSignInTokenSignsInAsAVerifiedUser() throws Exception {
        var clientId = "local-" + UUID.randomUUID();

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token(clientId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(clientId + "@example.com"))
                .andExpect(jsonPath("$.displayName").value("Local Developer"))
                .andExpect(jsonPath("$.status").value("active"));
    }

    /**
     * The web app's local sign-in (web/src/auth/oidc.ts, ADR 0018): an authorization code flow with PKCE, where
     * {@code login_hint} names the invented user, gives an ID token for that same user.
     */
    private String browserIdToken(String user) throws Exception {
        var verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        var challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        var redirect = "http://localhost:3000/sign-in/finish/";
        try (var http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            var authorize = http.send(
                    HttpRequest.newBuilder(URI.create(localSignIn.issuer() + "/authorize?client_id=idleo-local"
                                    + "&response_type=code&scope=openid&state=s1&login_hint=" + user
                                    + "&redirect_uri="
                                    + java.net.URLEncoder.encode(redirect, java.nio.charset.StandardCharsets.UTF_8)
                                    + "&code_challenge=" + challenge + "&code_challenge_method=S256"))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            var location = authorize.headers().firstValue("Location").orElseThrow();
            assertThat(location).startsWith(redirect);
            var code = location.replaceFirst(".*[?&]code=([^&]+).*", "$1");
            var token = http.send(
                    HttpRequest.newBuilder(URI.create(localSignIn.issuer() + "/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "grant_type=authorization_code&client_id=idleo-local"
                                            + "&code=" + code + "&code_verifier=" + verifier + "&login_hint=" + user
                                            + "&auth_time=" + clock.instant().getEpochSecond()
                                            + "&redirect_uri="
                                            + java.net.URLEncoder.encode(
                                                    redirect, java.nio.charset.StandardCharsets.UTF_8)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(token.statusCode()).isEqualTo(200);
            return JsonMapper.builder()
                    .build()
                    .readTree(token.body())
                    .get("id_token")
                    .asString();
        }
    }

    @Test
    void theWebAppsLocalSignInIsTheSameUserAsTheTokenTheScriptsUse() throws Exception {
        var user = "local-" + UUID.randomUUID();

        var fromBrowser = JsonMapper.builder()
                .build()
                .readTree(mvc.perform(get("/v1/me").header("Authorization", "Bearer " + browserIdToken(user)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.email").value(user + "@example.com"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fromBrowser.get("id").asString()));
    }

    @Test
    void theProdGuardWouldRefuseThisProfilesDevelopmentSettings() {
        assertThat(ProductionSettingsGuard.problems(environment))
                .contains("agentgateway.allow-development-hosts is true")
                .anyMatch(problem -> problem.startsWith("identity issuer " + localSignIn.issuer()));
    }

    /**
     * Deleting an account asks for a sign-in within the last 10 minutes (ADR 0037), read from the token's {@code
     * auth_time}: the development server sets it, as Identity Platform does, so a local sign-in counts as fresh and
     * goes in the security log once.
     */
    @Test
    void aDevelopmentSignInCarriesItsSignInTimeAndIsLoggedOnce() throws Exception {
        var clientId = "local-" + UUID.randomUUID();
        long before = clock.instant().getEpochSecond();
        var token = token(clientId);
        long after = clock.instant().getEpochSecond();
        var claims = JsonMapper.builder()
                .build()
                .readTree(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        // The sign-in server reads the same clock, and a second may turn while it signs.
        assertThat(Long.parseLong(claims.get("auth_time").asString())).isBetween(before, after);

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                        select count(*) from identity.security_events s join identity.users u on u.id = s.user_id
                        where u.email = ? and s.kind = 'sign_in'
                        """, Integer.class, clientId + "@example.com"))
                .isOne();
    }
}
