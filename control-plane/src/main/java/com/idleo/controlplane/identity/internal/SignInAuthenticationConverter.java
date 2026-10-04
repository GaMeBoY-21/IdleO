package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.CurrentUser;
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
        return switch (signIn.signIn(claims)) {
            case SignInOutcome.Active active -> IdentityAuthenticationToken.allowed(new CurrentUser(active.userId()));
            case SignInOutcome.Suspended _ -> IdentityAuthenticationToken.denied(SUSPENDED);
            case SignInOutcome.EmailNotVerified _ -> IdentityAuthenticationToken.denied(EMAIL_NOT_VERIFIED);
        };
    }
}
