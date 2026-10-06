package com.idleo.controlplane.identity;

import java.util.UUID;

/** What other modules may ask about users, by our own user id (ADR 0009). */
public interface UserDirectory {

    /** Whether a user with this id exists, whatever their status. */
    boolean exists(UUID userId);
}
