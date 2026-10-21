package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Checks a bearer value that is an API key (ADR 0025). An unknown or revoked key is a 401, like a bad token; a
 * suspended owner's key is refused as their token is, and a waiting owner's key is limited as their token is.
 */
@Component
class ApiKeyAuthentication implements AuthenticationManager {

    private final ApiKeyStore keys;

    ApiKeyAuthentication(ApiKeyStore keys) {
        this.keys = keys;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var bearer = ((BearerTokenAuthenticationToken) authentication).getToken();
        var use = keys.use(bearer).orElseThrow(() -> new InvalidBearerTokenException("unknown or revoked API key"));
        var user = new CurrentUser(use.userId());
        var credential =
                new IdentityAuthenticationToken.KeyCredential(use.keyId(), use.scope() == ApiKeyStore.Scope.READ);
        return switch (use.userStatus()) {
            case SUSPENDED, ERASED ->
                IdentityAuthenticationToken.denied(Problem.ACCOUNT_SUSPENDED, SignInAuthenticationConverter.SUSPENDED);
            case WAITING -> IdentityAuthenticationToken.withKey(user, true, credential);
            case ACTIVE -> IdentityAuthenticationToken.withKey(user, false, credential);
        };
    }
}
