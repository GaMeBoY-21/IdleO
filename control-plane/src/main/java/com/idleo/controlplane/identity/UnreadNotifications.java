package com.idleo.controlplane.identity;

import java.util.UUID;

/**
 * A port the identity module declares and the notifications module implements (ADR 0026): how many messages a
 * person hasn't read, for {@code GET /v1/me}. Notifications already depends on identity, so this keeps the
 * dependency one way.
 */
public interface UnreadNotifications {

    int countFor(UUID userId);
}
