package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.hosts.HostConnections;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The hosts module's {@link HostConnections}, from the presence history (ADR 0056): each new stream is an online row. */
@Component
class PresenceConnections implements HostConnections {

    private final JdbcClient jdbc;

    PresenceConnections(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Long> streamsOpened(Collection<UUID> hostIds, Instant since) {
        var opened = new HashMap<UUID, Long>();
        if (hostIds.isEmpty()) {
            return opened;
        }
        jdbc.sql("""
                        select host_id, count(*) as opened from agentgateway.presence_changes
                        where host_id in (:hosts) and state = 'online' and at >= :since
                        group by host_id
                        """)
                .param("hosts", hostIds)
                .param("since", Timestamp.from(since))
                .query(rs -> {
                    opened.put(rs.getObject("host_id", UUID.class), rs.getLong("opened"));
                });
        return opened;
    }
}
