package com.idleo.controlplane.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * The checks every ID token passes after its signature, per Google's rules for third-party
 * verification (ADR 0009), which any OIDC provider also meets.
 */
final class IdTokenValidators {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private IdTokenValidators() {}

    static OAuth2TokenValidator<Jwt> forIssuer(String issuer, String audience, Clock clock) {
        var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        return new DelegatingOAuth2TokenValidator<>(List.of(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<Instant>(JwtClaimNames.IAT, iat -> iat != null && notInTheFuture(iat, clock)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()),
                // Optional claim (Google and most providers send it); if present, not in the future.
                new JwtClaimValidator<Object>(
                        "auth_time", authTime -> authTime == null || authTimeNotInTheFuture(authTime, clock))));
    }

    private static boolean notInTheFuture(Instant instant, Clock clock) {
        return !instant.isAfter(Instant.now(clock).plus(CLOCK_SKEW));
    }

    private static boolean authTimeNotInTheFuture(Object authTime, Clock clock) {
        if (authTime instanceof Instant instant) {
            return notInTheFuture(instant, clock);
        }
        if (authTime instanceof Number seconds) {
            return notInTheFuture(Instant.ofEpochSecond(seconds.longValue()), clock);
        }
        return false;
    }
}
