package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.CurrentUser;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The result of a verified ID token. It holds our user, never the token or the provider's ids. A
 * token can be valid while access is still denied (a suspended account, an unverified email); the
 * denial is then the reason, written for the client.
 */
final class IdentityAuthenticationToken extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final @Nullable CurrentUser user;
    private final @Nullable String denial;

    private IdentityAuthenticationToken(@Nullable CurrentUser user, @Nullable String denial) {
        super(List.of());
        this.user = user;
        this.denial = denial;
        setAuthenticated(true);
    }

    static IdentityAuthenticationToken allowed(CurrentUser user) {
        return new IdentityAuthenticationToken(user, null);
    }

    static IdentityAuthenticationToken denied(String reason) {
        return new IdentityAuthenticationToken(null, reason);
    }

    Optional<String> denial() {
        return Optional.ofNullable(denial);
    }

    @Override
    public @Nullable Object getPrincipal() {
        return user;
    }

    @Override
    public @Nullable Object getCredentials() {
        return null;
    }
}
