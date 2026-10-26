package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.agentgateway.SessionImagePulls;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How far a host has got downloading the renter's own image at a session's start (ADR 0042), from its heartbeats: kept on
 * the attempt, only from the host that runs it and only while the session is PROVISIONING, and shown on the session's
 * screen. The download is never charged: minute 0 starts when the job does.
 */
@Component
class ImagePullProgress implements SessionImagePulls {

    private final JdbcClient jdbc;
    private final SessionHints hints;

    ImagePullProgress(JdbcClient jdbc, SessionHints hints) {
        this.jdbc = jdbc;
        this.hints = hints;
    }

    @Override
    public void onImagePull(UUID hostId, UUID sessionId, int attempt, long doneBytes, long totalBytes) {
        int changed = jdbc.sql("""
                        update sessions.attempts a
                        set image_done_bytes = :done, image_total_bytes = :total
                        from sessions.sessions s
                        where s.id = a.session_id and a.session_id = :sessionId and a.attempt = :attempt
                          and a.host_id = :hostId and s.attempt = a.attempt and s.state = 'PROVISIONING'
                          and s.image_id is not null
                          and (a.image_done_bytes <> :done or a.image_total_bytes <> :total)
                        """)
                .param("done", doneBytes)
                .param("total", totalBytes)
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .param("hostId", hostId)
                .update();
        if (changed > 0) {
            hints.changed(sessionId);
        }
    }
}
