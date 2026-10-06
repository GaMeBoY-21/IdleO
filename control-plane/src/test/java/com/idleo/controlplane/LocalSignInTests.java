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

    /** Its tokens carry the real time; other tests move the shared clock. */
    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    private String token(String clientId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(localSignIn.issuer() + "/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&client_id=" + clientId + "&client_secret=unused&scope=openid"))
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

    @Test
    void theProdGuardWouldRefuseThisProfilesDevelopmentSettings() {
        assertThat(ProductionSettingsGuard.problems(environment))
                .contains("agentgateway.allow-development-hosts is true")
                .anyMatch(problem -> problem.startsWith("identity issuer " + localSignIn.issuer()));
    }
}
