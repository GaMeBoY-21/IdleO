package com.idleo.controlplane.identity.internal;

import org.jspecify.annotations.Nullable;

/** What sign-in needs from a verified ID token. Never leaves the identity module. */
record SignInClaims(
        String issuer,
        String subject,
        @Nullable String email,
        boolean emailVerified,
        @Nullable String displayName) {}
