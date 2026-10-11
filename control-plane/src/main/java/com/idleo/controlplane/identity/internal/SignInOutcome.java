package com.idleo.controlplane.identity.internal;

import java.util.UUID;

sealed interface SignInOutcome {

    record Active(UUID userId) implements SignInOutcome {}

    record Waiting(UUID userId) implements SignInOutcome {}

    record Suspended(UUID userId) implements SignInOutcome {}

    record EmailNotVerified() implements SignInOutcome {}
}
