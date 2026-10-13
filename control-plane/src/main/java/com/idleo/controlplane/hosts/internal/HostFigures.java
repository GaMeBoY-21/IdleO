package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.HostPresence;
import com.idleo.controlplane.identity.DashboardFigures;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The hosts module's figure for the admin dashboard (ADR 0027): hosts whose agent is connected now. */
@Component
class HostFigures implements DashboardFigures {

    private final JdbcClient jdbc;
    private final HostPresence presence;

    HostFigures(JdbcClient jdbc, HostPresence presence) {
        this.jdbc = jdbc;
        this.presence = presence;
    }

    @Override
    public Map<Figure, Long> figures() {
        var hosts = jdbc.sql("select id from hosts.hosts where status <> 'removed'")
                .query(UUID.class)
                .list();
        return Map.of(Figure.HOSTS_ONLINE, (long) presence.online(hosts).size());
    }
}
