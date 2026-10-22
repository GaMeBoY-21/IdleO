package com.idleo.controlplane.identity.internal;

import java.util.UUID;

/** Who a verified ID token belongs to. {@code identityId} is our row for its issuer and subject. */
sealed interface SignInOutcome {

    record Active(UUID userId, UUID identityId) implements SignInOutcome {}

    record Waiting(UUID userId, UUID identityId) implements SignInOutcome {}

    /** Suspended or banned: signed in, but limited to a few of its own calls (ADR 0038). */
    record Held(UUID userId, UUID identityId) implements SignInOutcome {}

    /** Erased: nothing can sign in as it. */
    record Suspended(UUID userId) implements SignInOutcome {}

    record EmailNotVerified() implements SignInOutcome {}
}
