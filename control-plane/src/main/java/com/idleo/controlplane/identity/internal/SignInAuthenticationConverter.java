package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.SecurityLog;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Maps a verified ID token to our user, creating the user on first sign-in. */
@Component
class SignInAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String SUSPENDED = "This account is suspended.";
    static final String EMAIL_NOT_VERIFIED =
            "Your email address must be verified with the sign-in provider before you can use this account.";

    private final SignInService signIn;
    private final SecurityLog security;

    SignInAuthenticationConverter(SignInService signIn, SecurityLog security) {
        this.signIn = signIn;
        this.security = security;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        var claims = new SignInClaims(
                Objects.requireNonNull(token.getClaimAsString("iss")),
                token.getSubject(),
                token.getClaimAsString("email"),
                Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified")),
                token.getClaimAsString("name"),
                authTime(token));
        var methods = signInMethods(token);
        return switch (signIn.signIn(claims)) {
            case SignInOutcome.Active active ->
                IdentityAuthenticationToken.allowed(
                        new CurrentUser(active.userId()), methods, active.identityId(), claims.authTime());
            case SignInOutcome.Waiting waiting ->
                IdentityAuthenticationToken.waiting(
                        new CurrentUser(waiting.userId()), methods, waiting.identityId(), claims.authTime());
            case SignInOutcome.Suspended suspended -> {
                security.record(SecurityLog.Kind.SIGN_IN_REFUSED, suspended.userId(), "suspended");
                yield IdentityAuthenticationToken.denied(Problem.ACCOUNT_SUSPENDED, SUSPENDED);
            }
            case SignInOutcome.EmailNotVerified _ -> {
                security.record(SecurityLog.Kind.SIGN_IN_REFUSED, null, "email_not_verified");
                yield IdentityAuthenticationToken.denied(Problem.EMAIL_NOT_VERIFIED, EMAIL_NOT_VERIFIED);
            }
        };
    }

    /** When the person signed in at the provider: the token's {@code auth_time}; null when it has none. */
    static @Nullable Instant authTime(Jwt token) {
        return IdTokenValidators.authTime(token.getClaims().get("auth_time"));
    }

    /**
     * How this sign-in happened, from Identity Platform's {@code firebase.sign_in_provider} claim: Google, or
     * an email link (the provider id Identity Platform gives it is {@code password}; we have no passwords).
     * Any other provider is left out rather than guessed.
     */
    static List<String> signInMethods(Jwt token) {
        var firebase = token.getClaimAsMap("firebase");
        var provider = firebase == null ? null : firebase.get("sign_in_provider");
        return switch (String.valueOf(provider)) {
            case "google.com" -> List.of("google");
            case "password" -> List.of("email-link");
            default -> List.of();
        };
    }
}
