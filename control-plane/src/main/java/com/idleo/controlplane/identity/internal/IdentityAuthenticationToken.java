package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import java.time.Instant;
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
    private final @Nullable UUID identityId;
    private final @Nullable Instant authTime;

    private IdentityAuthenticationToken(
            @Nullable CurrentUser user,
            @Nullable Denial denial,
            boolean waiting,
            List<String> signInMethods,
            @Nullable KeyCredential key,
            @Nullable UUID identityId,
            @Nullable Instant authTime) {
        super(List.of());
        this.user = user;
        this.denial = denial;
        this.waiting = waiting;
        this.signInMethods = new ArrayList<>(signInMethods);
        this.key = key;
        this.identityId = identityId;
        this.authTime = authTime;
        setAuthenticated(true);
    }

    /**
     * An active user, signed in with these methods ({@code google} or {@code email-link}), through our sign-in
     * identity {@code identityId} (the token's issuer and subject), at {@code authTime} (the token's {@code
     * auth_time}).
     */
    static IdentityAuthenticationToken allowed(
            CurrentUser user, List<String> signInMethods, UUID identityId, @Nullable Instant authTime) {
        return new IdentityAuthenticationToken(user, null, false, signInMethods, null, identityId, authTime);
    }

    /** A user who hasn't entered an invite code yet: they may only reach their own account. */
    static IdentityAuthenticationToken waiting(
            CurrentUser user, List<String> signInMethods, UUID identityId, @Nullable Instant authTime) {
        return new IdentityAuthenticationToken(user, null, true, signInMethods, null, identityId, authTime);
    }

    /** A user calling with an API key; {@code waiting} as for their tokens. */
    static IdentityAuthenticationToken withKey(CurrentUser user, boolean waiting, KeyCredential key) {
        return new IdentityAuthenticationToken(user, null, waiting, List.of(), key, null, null);
    }

    static IdentityAuthenticationToken denied(Problem problem, String reason) {
        return new IdentityAuthenticationToken(null, new Denial(problem, reason), false, List.of(), null, null, null);
    }

    /** When the person signed in at the provider ({@code auth_time}); empty for an API key or a token without it. */
    Optional<Instant> authTime() {
        return Optional.ofNullable(authTime);
    }

    /** The sign-in identity (issuer and subject) of an ID token; empty for an API key or a denial. */
    Optional<UUID> identityId() {
        return Optional.ofNullable(identityId);
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
