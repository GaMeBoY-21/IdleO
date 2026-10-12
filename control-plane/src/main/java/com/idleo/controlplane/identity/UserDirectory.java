package com.idleo.controlplane.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** What other modules may ask about users, by our own user id (ADR 0009). */
public interface UserDirectory {

    /** Whether a user with this id exists, whatever their status. */
    boolean exists(UUID userId);

    /** When the user's account was made, by their first sign-in; empty for no such user. */
    Optional<Instant> createdAt(UUID userId);

    /** The address to write to: the user's email, when there is one and the sign-in provider has verified it. */
    Optional<String> verifiedEmail(UUID userId);
}
