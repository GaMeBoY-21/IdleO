package com.idleo.controlplane.identity.internal;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What sign-in needs from a verified ID token. Never leaves the identity module. {@code authTime} is when the person
 * last signed in at the provider (the token's {@code auth_time}), not when the token was issued.
 */
record SignInClaims(
        String issuer,
        String subject,
        @Nullable String email,
        boolean emailVerified,
        @Nullable String displayName,
        @Nullable Instant authTime) {}
