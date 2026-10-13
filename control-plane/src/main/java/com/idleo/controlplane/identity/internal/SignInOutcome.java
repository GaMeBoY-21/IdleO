package com.idleo.controlplane.identity.internal;

import java.util.UUID;

/** Who a verified ID token belongs to. {@code identityId} is our row for its issuer and subject. */
sealed interface SignInOutcome {

    record Active(UUID userId, UUID identityId) implements SignInOutcome {}

    record Waiting(UUID userId, UUID identityId) implements SignInOutcome {}

    record Suspended(UUID userId) implements SignInOutcome {}

    record EmailNotVerified() implements SignInOutcome {}
}
