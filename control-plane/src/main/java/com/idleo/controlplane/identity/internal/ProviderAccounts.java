package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.SignInAccounts;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The person's accounts at the sign-in provider (ADR 0037). An Identity Platform account (its issuer {@code
 * https://securetoken.google.com/<project>}) is deleted through Google's Identity Toolkit admin API ({@code
 * accounts:delete}), with an access token for the service account in {@code identity.provider-admin.credentials}
 * ({@code IDENTITY_PROVIDER_ADMIN_CREDENTIALS}: the service account's JSON key, or {@code file:} and its path), signed
 * here (RFC 7523), so no Google library is needed. The prod profile refuses to start without it. Any other issuer is the
 * development sign-in server's, which has nothing to delete: the call is recorded and logged.
 */
@Component
@EnableConfigurationProperties(ProviderAccounts.Settings.class)
class ProviderAccounts implements SignInAccounts {

    static final String GOOGLE_ISSUER = "https://securetoken.google.com/";
    private static final String SCOPE = "https://www.googleapis.com/auth/identitytoolkit";
    private static final Logger log = LoggerFactory.getLogger(ProviderAccounts.class);

    /**
     * @param credentials the service account's JSON key, or {@code file:} and a path to it; empty where nothing is
     *     deleted at a real provider
     * @param apiUrl Identity Toolkit's address (a test's stand-in in tests)
     */
    @ConfigurationProperties("identity.provider-admin")
    record Settings(
            @DefaultValue("") String credentials,

            @DefaultValue("https://identitytoolkit.googleapis.com")
            URI apiUrl) {}

    private final JdbcClient jdbc;
    private final Settings settings;
    private final Clock clock;
    private final JsonMapper json;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<ProviderAccount> recorded = new CopyOnWriteArrayList<>();

    ProviderAccounts(JdbcClient jdbc, Settings settings, Clock clock, JsonMapper json) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public List<ProviderAccount> of(UUID userId) {
        return jdbc.sql("select issuer, subject from identity.external_identities where user_id = :user order by id")
                .param("user", userId)
                .query((rs, n) -> new ProviderAccount(rs.getString(1), rs.getString(2)))
                .list();
    }

    @Override
    public void delete(ProviderAccount account) {
        if (!account.issuer().startsWith(GOOGLE_ISSUER)) {
            recorded.add(account);
            log.info("Deleted a development sign-in account (recorded only; that server keeps no accounts)");
            return;
        }
        var project = account.issuer().substring(GOOGLE_ISSUER.length());
        try {
            var token = accessToken();
            var response = http.send(
                    HttpRequest.newBuilder(settings.apiUrl().resolve("/v1/projects/" + project + "/accounts:delete"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    json.writeValueAsString(java.util.Map.of("localId", account.subject()))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.body().contains("USER_NOT_FOUND")) {
                return;
            }
            throw new IllegalStateException("the provider refused to delete the account: " + response.statusCode());
        } catch (IOException e) {
            throw new IllegalStateException("the provider couldn't be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** The development calls, for tests. */
    List<ProviderAccount> recorded() {
        return List.copyOf(recorded);
    }

    private String accessToken() throws IOException, InterruptedException {
        var key = serviceAccount();
        var now = Instant.now(clock);
        var assertion = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyId()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(key.clientEmail())
                        .audience(key.tokenUri())
                        .claim("scope", SCOPE)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(600)))
                        .build());
        try {
            assertion.sign(new RSASSASigner(key.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("the service account's key can't sign", e);
        }
        var response = http.send(
                HttpRequest.newBuilder(URI.create(key.tokenUri()))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type="
                                + URLEncoder.encode(
                                        "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                                + "&assertion=" + assertion.serialize()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("the provider gave no access token: " + response.statusCode());
        }
        return json.readTree(response.body()).get("access_token").asString();
    }

    private record ServiceAccount(
            String clientEmail, @Nullable String keyId, String tokenUri, RSAPrivateKey privateKey) {}

    private ServiceAccount serviceAccount() throws IOException {
        var text = settings.credentials().strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("identity.provider-admin.credentials isn't set");
        }
        if (text.startsWith("file:")) {
            text = Files.readString(Path.of(text.substring("file:".length())));
        }
        var node = json.readTree(text);
        var pem = node.get("private_key")
                .asString()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            var key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            var keyId = node.has("private_key_id") ? node.get("private_key_id").asString() : null;
            var tokenUri =
                    node.has("token_uri") ? node.get("token_uri").asString() : "https://oauth2.googleapis.com/token";
            return new ServiceAccount(node.get("client_email").asString(), keyId, tokenUri, key);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("the service account's private key can't be read", e);
        }
    }
}
