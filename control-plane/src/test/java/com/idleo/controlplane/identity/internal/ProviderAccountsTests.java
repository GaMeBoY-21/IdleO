package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.identity.SignInAccounts.ProviderAccount;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deleting an Identity Platform account (ADR 0037): an access token for the service account, from an assertion signed
 * with its key (RFC 7523), then {@code accounts:delete} for the person's uid, against a stand-in for Google. One already
 * gone counts as deleted; any other refusal is an error, so the erasure tries again.
 */
class ProviderAccountsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer google;
    private final List<String> deleted = new ArrayList<>();
    private RSAPublicKey publicKey;
    private String serviceAccount;
    private int deleteStatus = 200;
    private String deleteBody = "{}";

    @BeforeEach
    void start() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        publicKey = (RSAPublicKey) keys.getPublic();
        google = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var base = "http://127.0.0.1:" + google.getAddress().getPort();
        serviceAccount = JSON.writeValueAsString(Map.of(
                "type",
                "service_account",
                "client_email",
                "idleo-erasure@project.iam.gserviceaccount.com",
                "private_key_id",
                "key-1",
                "token_uri",
                base + "/token",
                "private_key",
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder()
                                .encodeToString(keys.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----\n"));
        google.createContext("/token", exchange -> {
            var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var assertion = URLDecoder.decode(form.replaceFirst(".*assertion=", ""), StandardCharsets.UTF_8);
            boolean valid;
            try {
                var jwt = SignedJWT.parse(assertion);
                valid = jwt.verify(new RSASSAVerifier(publicKey))
                        && jwt.getJWTClaimsSet().getIssuer().equals("idleo-erasure@project.iam.gserviceaccount.com")
                        && jwt.getJWTClaimsSet().getAudience().contains(base + "/token")
                        && form.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
            } catch (Exception e) {
                valid = false;
            }
            var body = valid ? "{\"access_token\":\"granted\"}" : "{\"error\":\"invalid_grant\"}";
            exchange.sendResponseHeaders(valid ? 200 : 400, body.length());
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        google.createContext("/v1/projects/idleo-prod/accounts:delete", exchange -> {
            var authorised =
                    "Bearer granted".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            if (authorised) {
                deleted.add(request.get("localId").asString());
            }
            var body = authorised ? deleteBody : "{}";
            exchange.sendResponseHeaders(authorised ? deleteStatus : 401, body.length());
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        google.start();
    }

    @AfterEach
    void stop() {
        google.stop(0);
    }

    private ProviderAccounts accounts(String credentials) {
        return new ProviderAccounts(
                JdbcClient.create(new org.springframework.jdbc.datasource.SimpleDriverDataSource()),
                new ProviderAccounts.Settings(
                        credentials,
                        URI.create("http://127.0.0.1:" + google.getAddress().getPort())),
                Clock.systemUTC(),
                JSON);
    }

    @Test
    void deletesAnIdentityPlatformAccountWithTheServiceAccountsToken() {
        accounts(serviceAccount).delete(new ProviderAccount("https://securetoken.google.com/idleo-prod", "uid-asha"));
        assertThat(deleted).containsExactly("uid-asha");
    }

    @Test
    void anAccountAlreadyGoneCountsAsDeletedAndAnyOtherRefusalIsAnError() {
        deleteStatus = 400;
        deleteBody = "{\"error\":{\"message\":\"USER_NOT_FOUND\"}}";
        accounts(serviceAccount).delete(new ProviderAccount("https://securetoken.google.com/idleo-prod", "uid-gone"));
        deleteBody = "{\"error\":{\"message\":\"PERMISSION_DENIED\"}}";
        assertThatThrownBy(() -> accounts(serviceAccount)
                        .delete(new ProviderAccount("https://securetoken.google.com/idleo-prod", "uid-asha")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutCredentialsItRefusesAndTheDevelopmentServerIsOnlyRecorded() {
        assertThatThrownBy(() -> accounts("")
                        .delete(new ProviderAccount("https://securetoken.google.com/idleo-prod", "uid-asha")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity.provider-admin.credentials");
        var development = accounts("");
        development.delete(new ProviderAccount("http://localhost:8099/idleo-local", "asha"));
        assertThat(development.recorded())
                .containsExactly(new ProviderAccount("http://localhost:8099/idleo-local", "asha"));
        assertThat(deleted).isEmpty();
    }
}
