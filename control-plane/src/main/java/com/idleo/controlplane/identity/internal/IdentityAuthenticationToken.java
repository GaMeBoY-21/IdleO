package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The result of a verified ID token. It holds our user, never the token or the provider's ids. A
 * token can be valid while access is still denied (a suspended account, an unverified email); the
 * denial is then the reason, written for the client, with its problem type.
 */
final class IdentityAuthenticationToken extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    /** Why access is denied: the problem type and a message for the client. */
    record Denial(Problem problem, String message) implements java.io.Serializable {}

    private final @Nullable CurrentUser user;
    private final @Nullable Denial denial;

    private IdentityAuthenticationToken(@Nullable CurrentUser user, @Nullable Denial denial) {
        super(List.of());
        this.user = user;
        this.denial = denial;
        setAuthenticated(true);
    }

    static IdentityAuthenticationToken allowed(CurrentUser user) {
        return new IdentityAuthenticationToken(user, null);
    }

    static IdentityAuthenticationToken denied(Problem problem, String reason) {
        return new IdentityAuthenticationToken(null, new Denial(problem, reason));
    }

    Optional<Denial> denial() {
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
