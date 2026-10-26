package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.images.ImageBlocked;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A renter's own image blocked after it was booked (ADR 0042): every session of it that hasn't started running is
 * cancelled ({@code image_blocked}), its hold released and nothing charged. A session already running goes on; its host's
 * runner judges its processes by the same blocklist.
 */
@Component
class ImageBlockedSessions {

    private static final Logger log = LoggerFactory.getLogger(ImageBlockedSessions.class);

    private final JdbcClient jdbc;
    private final SessionStore store;
    private final SessionService sessions;

    ImageBlockedSessions(JdbcClient jdbc, SessionStore store, SessionService sessions) {
        this.jdbc = jdbc;
        this.store = store;
        this.sessions = sessions;
    }

    @ApplicationModuleListener
    void on(ImageBlocked blocked) {
        var imageIds =
                blocked.images().stream().map(ImageBlocked.Blocked::imageId).toList();
        var waiting = jdbc.sql("""
                        select id from sessions.sessions
                        where image_id in (:images) and state in ('REQUESTED', 'MATCHED', 'PROVISIONING')
                        for update
                        """).param("images", imageIds).query(UUID.class).list();
        for (var sessionId : waiting) {
            store.find(sessionId).ifPresent(session -> {
                if (sessions.cancelled(session, "image_blocked")) {
                    log.info("Cancelled session {}: its image was blocked", sessionId);
                }
            });
        }
    }
}
