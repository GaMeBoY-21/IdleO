package com.idleo.controlplane;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Two local OIDC issuers for tests, so tests never call Google. Each serves a real discovery document
 * and JWK set over HTTP and signs ID tokens with its own RSA key, so the application's real
 * verification path runs: discovery, signature, issuer, audience, expiry.
 */
final class TestIssuers {

    static final String AUDIENCE_A = "demo-project";
    static final String AUDIENCE_B = "demo-project-b";

    private static final TestIssuers INSTANCE = new TestIssuers();

    final String issuerA;
    final String issuerB;
    /** An issuer nobody trusts, served like the others. */
    final String untrustedIssuer;

    final RSAKey keyA;
    final RSAKey keyB;
    final RSAKey untrustedKey;

    private TestIssuers() {
        try {
            keyA = new RSAKeyGenerator(2048).keyID("key-a").generate();
            keyB = new RSAKeyGenerator(2048).keyID("key-b").generate();
            untrustedKey = new RSAKeyGenerator(2048).keyID("key-untrusted").generate();
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            var base = "http://localhost:" + server.getAddress().getPort();
            issuerA = base + "/issuer-a";
            issuerB = base + "/issuer-b";
            untrustedIssuer = base + "/issuer-untrusted";
            serve(server, "/issuer-a", issuerA, keyA);
            serve(server, "/issuer-b", issuerB, keyB);
            serve(server, "/issuer-untrusted", untrustedIssuer, untrustedKey);
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static TestIssuers get() {
        return INSTANCE;
    }

    /** A valid ID token from issuer A for a new subject with a verified email. */
    String validToken() {
        return token(claims -> {});
    }

    /** An ID token from issuer A, signed with key A, after {@code customize} adjusts the claims. */
    String token(Consumer<JWTClaimsSet.Builder> customize) {
        return token(keyA, customize);
    }

    /** A valid-looking issuer A token that claims key A's id but is signed with a key A never published. */
    String forgedToken() {
        return token(untrustedKey, keyA.getKeyID(), claims -> {});
    }

    /** An ID token signed with {@code key}; the claims default to a valid issuer A token. */
    String token(RSAKey key, Consumer<JWTClaimsSet.Builder> customize) {
        return token(key, key.getKeyID(), customize);
    }

    private String token(RSAKey key, String keyId, Consumer<JWTClaimsSet.Builder> customize) {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var subject = UUID.randomUUID().toString();
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuerA)
                .audience(AUDIENCE_A)
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("auth_time", now.getEpochSecond())
                .claim("email", "renter-" + subject + "@example.com")
                .claim("email_verified", true)
                .claim("name", "Demo Renter");
        customize.accept(claims);
        try {
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void serve(HttpServer server, String path, String issuer, RSAKey key) {
        var discovery = """
                {"issuer":"%s","jwks_uri":"%s/jwks","response_types_supported":["id_token"],\
                "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"]}""".formatted(issuer, issuer);
        var jwks = new JWKSet(key.toPublicJWK()).toString();
        respond(server, path + "/.well-known/openid-configuration", discovery);
        respond(server, path + "/jwks", jwks);
    }

    private static void respond(HttpServer server, String path, String json) {
        server.createContext(path, exchange -> {
            var body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }
}
