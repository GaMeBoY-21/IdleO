package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.hosts.HostSessionResults;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * How hosts' sessions have gone, for the host score (hosts' {@link HostSessionResults}).
 *
 * <p>A host finished a session when the attempt it ran was the one that completed it, whoever stopped it. It lost one
 * when an attempt ended with a lost reason: its lease lapsed, it went missing after a reconnect, or its agent fenced
 * or stopped the attempt. A host whose owner came back to the machine ({@code owner_returned}: a checkpoint, then the
 * session carried on elsewhere) lost nothing, and a job that failed or a renter who stopped it isn't the host's.
 */
@Service
class HostSessionResultsService implements HostSessionResults {

    private final JdbcClient jdbc;

    HostSessionResultsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Result> since(Collection<UUID> hostIds, Instant since) {
        if (hostIds.isEmpty()) {
            return List.of();
        }
        var results = new ArrayList<Result>();
        jdbc.sql("""
                        select a.host_id, s.state_changed_at as at from sessions.attempts a
                        join sessions.sessions s on s.id = a.session_id and s.attempt = a.attempt
                        where a.host_id in (:hostIds) and a.running_at is not null and a.lost_reason is null
                          and s.state = 'COMPLETED' and s.state_changed_at >= :since
                        """)
                .param("hostIds", hostIds)
                .param("since", Timestamp.from(since))
                .query(rs -> {
                    results.add(new Result(
                            rs.getObject("host_id", UUID.class),
                            rs.getTimestamp("at").toInstant(),
                            true));
                });
        jdbc.sql("""
                        select host_id, ended_at as at from sessions.attempts
                        where host_id in (:hostIds) and lost_reason is not null and lost_reason <> :ownerReturned
                          and ended_at >= :since
                        """)
                .param("hostIds", hostIds)
                .param("ownerReturned", SessionProgress.OWNER_RETURNED)
                .param("since", Timestamp.from(since))
                .query(rs -> {
                    results.add(new Result(
                            rs.getObject("host_id", UUID.class),
                            rs.getTimestamp("at").toInstant(),
                            false));
                });
        return results;
    }
}
