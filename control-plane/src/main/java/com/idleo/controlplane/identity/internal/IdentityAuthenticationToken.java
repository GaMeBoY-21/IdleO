package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The result of a verified ID token or API key. It holds our user, never the token, the key or the provider's ids. A
 * token can be valid while access is still denied (a suspended account, an unverified email); the
 * denial is then the reason, written for the client, with its problem type. A waiting user (no invite
 * code yet) is let through and then limited to their own account by {@link
 * IdentitySecurityConfiguration}.
 */
final class IdentityAuthenticationToken extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    /** Why access is denied: the problem type and a message for the client. */
    record Denial(Problem problem, String message) implements java.io.Serializable {}

    /** The API key a request was made with (ADR 0025): which one, and whether it may only read. */
    record KeyCredential(UUID keyId, boolean readOnly) implements java.io.Serializable {}

    private final @Nullable CurrentUser user;
    private final @Nullable Denial denial;
    private final boolean waiting;
    private final ArrayList<String> signInMethods;
    private final @Nullable KeyCredential key;

    private IdentityAuthenticationToken(
            @Nullable CurrentUser user,
            @Nullable Denial denial,
            boolean waiting,
            List<String> signInMethods,
            @Nullable KeyCredential key) {
        super(List.of());
        this.user = user;
        this.denial = denial;
        this.waiting = waiting;
        this.signInMethods = new ArrayList<>(signInMethods);
        this.key = key;
        setAuthenticated(true);
    }

    /** An active user, signed in with these methods ({@code google} or {@code email-link}). */
    static IdentityAuthenticationToken allowed(CurrentUser user, List<String> signInMethods) {
        return new IdentityAuthenticationToken(user, null, false, signInMethods, null);
    }

    /** A user who hasn't entered an invite code yet: they may only reach their own account. */
    static IdentityAuthenticationToken waiting(CurrentUser user, List<String> signInMethods) {
        return new IdentityAuthenticationToken(user, null, true, signInMethods, null);
    }

    /** A user calling with an API key; {@code waiting} as for their tokens. */
    static IdentityAuthenticationToken withKey(CurrentUser user, boolean waiting, KeyCredential key) {
        return new IdentityAuthenticationToken(user, null, waiting, List.of(), key);
    }

    static IdentityAuthenticationToken denied(Problem problem, String reason) {
        return new IdentityAuthenticationToken(null, new Denial(problem, reason), false, List.of(), null);
    }

    /** The API key this request was made with; empty for a browser's ID token. */
    Optional<KeyCredential> key() {
        return Optional.ofNullable(key);
    }

    boolean isWaiting() {
        return waiting;
    }

    List<String> signInMethods() {
        return List.copyOf(signInMethods);
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
