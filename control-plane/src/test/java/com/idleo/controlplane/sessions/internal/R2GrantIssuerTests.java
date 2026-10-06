package com.idleo.controlplane.sessions.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

/**
 * The R2 adapter against Cloudflare's documented contract for temporary access credentials (ADR 0016):
 * https://developers.cloudflare.com/api/resources/r2/subresources/temporary_credentials/methods/create/. The
 * response fixture follows the documented shape, with invented values. Never run against a live account.
 */
class R2GrantIssuerTests {

    private static final String PATH = "/client/v4/accounts/fixture-account/r2/temp-access-credentials";

    private WireMockServer cloudflare;

    @BeforeEach
    void start() {
        cloudflare = new WireMockServer(options().dynamicPort());
        cloudflare.start();
    }

    @AfterEach
    void stop() {
        cloudflare.stop();
    }

    private R2GrantIssuer issuer() {
        var properties = new StorageProperties(
                StorageProperties.Provider.R2,
                URI.create("https://fixture-account.r2.cloudflarestorage.com"),
                "idleo-fixture",
                "auto",
                "fixture-parent-key",
                "fixture-parent-secret",
                Duration.ofHours(1),
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                new StorageProperties.R2(
                        URI.create(cloudflare.baseUrl() + "/client/v4"),
                        "fixture-account",
                        "fixture-api-token",
                        "fixture-parent-key"));
        return new R2GrantIssuer(RestClient.builder(), properties);
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("r2/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void aGrantIsTheDocumentedRequestAndItsCredentialsComeFromTheResult() throws IOException {
        cloudflare.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("temp-access-credentials-response.json"))));

        var grant = issuer().issue(List.of("sessions/s1/attempt-2/"), true);

        cloudflare.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer fixture-api-token"))
                .withRequestBody(equalToJson("""
                        {"bucket": "idleo-fixture", "parentAccessKeyId": "fixture-parent-key",
                         "permission": "object-read-write", "ttlSeconds": 3600,
                         "prefixes": ["sessions/s1/attempt-2/"]}
                        """)));
        assertThat(grant.accessKeyId()).isEqualTo("fixture-access-key-id");
        assertThat(grant.secretAccessKey()).isEqualTo("fixture-secret-access-key");
        assertThat(grant.sessionToken()).isEqualTo("fixture-session-token");
        assertThat(grant.endpoint()).isEqualTo("https://fixture-account.r2.cloudflarestorage.com");
        assertThat(grant.prefixes()).containsExactly("sessions/s1/attempt-2/");
        assertThat(grant.toString()).doesNotContain("fixture-secret-access-key", "fixture-session-token");
    }

    @Test
    void aReadGrantAsksForObjectReadOnly() throws IOException {
        cloudflare.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("temp-access-credentials-response.json"))));

        issuer().issue(List.of("sessions/s1/input/", "sessions/s1/attempt-1/checkpoints/00000003/"), false);

        cloudflare.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"bucket": "idleo-fixture", "parentAccessKeyId": "fixture-parent-key",
                 "permission": "object-read-only", "ttlSeconds": 3600,
                 "prefixes": ["sessions/s1/input/", "sessions/s1/attempt-1/checkpoints/00000003/"]}
                """)));
    }

    @Test
    void aRefusalIsAnErrorThatCarriesNoSecret() throws IOException {
        cloudflare.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("temp-access-credentials-error.json"))));

        assertThatThrownBy(() -> issuer().issue(List.of("sessions/s1/attempt-1/"), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageNotContaining("fixture-api-token");
    }
}
