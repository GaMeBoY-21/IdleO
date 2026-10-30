package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.HostPresence;
import com.idleo.controlplane.identity.DashboardFigures;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The hosts module's figure for the admin dashboard (ADR 0027): hosts whose agent is connected now, or null (unknown)
 * while presence can't be read, so the dashboard never shows a fleet gone offline that isn't.
 */
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
        var seen = presence.seen(hosts);
        var figures = new EnumMap<Figure, Long>(Figure.class);
        figures.put(
                Figure.HOSTS_ONLINE, seen.known() ? (long) seen.connections().size() : null);
        return figures;
    }
}
