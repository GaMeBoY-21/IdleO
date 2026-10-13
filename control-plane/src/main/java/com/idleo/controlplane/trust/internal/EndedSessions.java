package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.sessions.SessionEnded;
import com.idleo.controlplane.sessions.SessionNeverRan;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trust module's own record of every session that ended (ADR 0028), kept from the sessions module's events so
 * nothing here reads that module's tables: whose it was, the host that ran its last attempt, and when it ran and
 * ended. A renter rates and reports only sessions found here. The listeners are durable and safe to run twice.
 */
@Component
class EndedSessions {

    /** A session ran long enough to be rated once it ended this long after its minute 0. */
    static final Duration RATEABLE_AFTER = Duration.ofMinutes(1);

    /**
     * @param hostId the host of its last attempt that ran; null if it never ran
     * @param startedAt its minute 0; null if it never ran
     */
    record EndedSession(
            UUID sessionId,
            UUID renterId,
            @Nullable UUID hostId,
            String state,
            @Nullable Instant startedAt,
            Instant endedAt) {

        /** It ran for at least a minute, so its renter can say how it went. */
        boolean ranAMinute() {
            return startedAt != null && !endedAt.isBefore(startedAt.plus(RATEABLE_AFTER));
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    EndedSessions(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(SessionEnded event) {
        record(event.sessionId(), event.renterId(), event.hostId(), event.state(), event.startedAt(), event.endedAt());
    }

    @ApplicationModuleListener
    void on(SessionNeverRan event) {
        record(event.sessionId(), event.renterId(), null, event.state(), null, event.endedAt());
    }

    private void record(
            UUID sessionId,
            UUID renterId,
            @Nullable UUID hostId,
            String state,
            @Nullable Instant startedAt,
            Instant endedAt) {
        jdbc.sql("""
                        insert into trust.ended_sessions
                            (session_id, renter_id, host_id, state, started_at, ended_at, recorded_at)
                        values (:sessionId, :renterId, :hostId, :state, :startedAt, :endedAt, :now)
                        on conflict (session_id) do nothing
                        """)
                .param("sessionId", sessionId)
                .param("renterId", renterId)
                .param("hostId", hostId)
                .param("state", state)
                .param("startedAt", startedAt == null ? null : Timestamp.from(startedAt))
                .param("endedAt", Timestamp.from(endedAt))
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
    }

    /** The renter's ended session; empty if it isn't theirs, or hasn't ended. */
    Optional<EndedSession> ofRenter(UUID renterId, UUID sessionId) {
        return find(sessionId, false).filter(session -> session.renterId().equals(renterId));
    }

    /** The renter's ended session, locked for the rest of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<EndedSession> lockOfRenter(UUID renterId, UUID sessionId) {
        return find(sessionId, true).filter(session -> session.renterId().equals(renterId));
    }

    Optional<EndedSession> find(UUID sessionId) {
        return find(sessionId, false);
    }

    private Optional<EndedSession> find(UUID sessionId, boolean lock) {
        return jdbc.sql("""
                        select session_id, renter_id, host_id, state, started_at, ended_at from trust.ended_sessions
                        where session_id = :sessionId
                        """ + (lock ? " for update" : ""))
                .param("sessionId", sessionId)
                .query((rs, n) -> new EndedSession(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("renter_id", UUID.class),
                        rs.getObject("host_id", UUID.class),
                        rs.getString("state"),
                        rs.getTimestamp("started_at") == null
                                ? null
                                : rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("ended_at").toInstant()))
                .optional();
    }
}
