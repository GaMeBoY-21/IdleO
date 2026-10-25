package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.hosts.HostScores;
import com.idleo.controlplane.sessions.AttemptLost;
import com.idleo.controlplane.sessions.SessionEnded;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Works a host's score out again when one of its sessions ends or one of its attempts is lost, from the durable events
 * sessions already publishes. The hosts module reads what happened through {@link HostSessionResultsService}.
 */
@Component
class HostScoreRefresh {

    private static final Logger log = LoggerFactory.getLogger(HostScoreRefresh.class);

    private final JdbcClient jdbc;
    private final HostScores scores;

    HostScoreRefresh(JdbcClient jdbc, HostScores scores) {
        this.jdbc = jdbc;
        this.scores = scores;
    }

    @ApplicationModuleListener
    void on(SessionEnded event) {
        scores.refresh(event.hostId());
    }

    @ApplicationModuleListener
    void on(AttemptLost event) {
        jdbc.sql("select host_id from sessions.attempts where session_id = :id and attempt = :attempt")
                .param("id", event.sessionId())
                .param("attempt", event.attempt())
                .query(UUID.class)
                .optional()
                .ifPresentOrElse(
                        scores::refresh,
                        () -> log.warn(
                                "Session {} attempt {} was lost but has no host", event.sessionId(), event.attempt()));
    }
}
