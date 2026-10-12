package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.identity.UnreadNotifications;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** How many messages a person hasn't read, for {@code GET /v1/me} (identity declares the port). */
@Service
class NotificationCounts implements UnreadNotifications {

    private final NotificationStore store;

    NotificationCounts(NotificationStore store) {
        this.store = store;
    }

    @Override
    public int countFor(UUID userId) {
        return store.unread(userId);
    }
}
