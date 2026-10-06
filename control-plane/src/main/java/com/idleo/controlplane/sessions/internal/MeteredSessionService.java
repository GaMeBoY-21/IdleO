package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.MeteredSessions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class MeteredSessionService implements MeteredSessions {

    private final SessionStore store;
    private final LeaseBook leases;
    private final LeaseProperties leaseProperties;
    private final JdbcClient jdbc;
    private final Checkpoints checkpoints;

    MeteredSessionService(
            SessionStore store,
            LeaseBook leases,
            LeaseProperties leaseProperties,
            JdbcClient jdbc,
            Checkpoints checkpoints) {
        this.store = store;
        this.leases = leases;
        this.leaseProperties = leaseProperties;
        this.jdbc = jdbc;
        this.checkpoints = checkpoints;
    }

    @Override
    public Optional<Instant> lastCompleteCheckpointAt(UUID sessionId, int attempt) {
        return checkpoints.newest(sessionId, attempt).map(Checkpoints.Complete::completedAt);
    }

    @Override
    public Duration leaseTtl() {
        return leaseProperties.ttl();
    }

    @Override
    public Optional<Session> session(UUID sessionId) {
        return store.find(sessionId)
                .map(session -> new Session(
                        session.id(),
                        session.renterId(),
                        session.maxSpendPaise(),
                        session.startedAt(),
                        session.attempt(),
                        session.hostId(),
                        !session.state().isFinal));
    }

    @Override
    public List<Attempt> attempts(UUID sessionId) {
        return jdbc.sql("""
                        select a.attempt, a.host_id, a.price_paise_per_min, a.minute_index_base, a.start_delivered_at,
                               a.running_at, a.ended_at
                        from sessions.attempts a
                        where a.session_id = :sessionId
                        order by a.attempt
                        """)
                .param("sessionId", sessionId)
                .query(MeteredSessionService::attempt)
                .list();
    }

    @Override
    public boolean leaseCovered(UUID sessionId, int attempt, Instant from, Instant until) {
        return leases.covered(sessionId, attempt, from, until);
    }

    private static Attempt attempt(ResultSet rs, int row) throws SQLException {
        return new Attempt(
                rs.getInt("attempt"),
                rs.getObject("host_id", UUID.class),
                rs.getInt("price_paise_per_min"),
                rs.getInt("minute_index_base"),
                instant(rs.getTimestamp("start_delivered_at")),
                instant(rs.getTimestamp("running_at")),
                instant(rs.getTimestamp("ended_at")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
