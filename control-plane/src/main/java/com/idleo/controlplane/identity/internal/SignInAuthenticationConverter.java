package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import java.util.List;
import java.util.Objects;
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

    SignInAuthenticationConverter(SignInService signIn) {
        this.signIn = signIn;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        var claims = new SignInClaims(
                Objects.requireNonNull(token.getClaimAsString("iss")),
                token.getSubject(),
                token.getClaimAsString("email"),
                Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified")),
                token.getClaimAsString("name"));
        var methods = signInMethods(token);
        return switch (signIn.signIn(claims)) {
            case SignInOutcome.Active active ->
                IdentityAuthenticationToken.allowed(new CurrentUser(active.userId()), methods);
            case SignInOutcome.Waiting waiting ->
                IdentityAuthenticationToken.waiting(new CurrentUser(waiting.userId()), methods);
            case SignInOutcome.Suspended _ -> IdentityAuthenticationToken.denied(Problem.ACCOUNT_SUSPENDED, SUSPENDED);
            case SignInOutcome.EmailNotVerified _ ->
                IdentityAuthenticationToken.denied(Problem.EMAIL_NOT_VERIFIED, EMAIL_NOT_VERIFIED);
        };
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
