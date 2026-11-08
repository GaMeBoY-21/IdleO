package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ControlRoom;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The hosts module's steps of the hosts' funnel (ADR 0056): owners who linked a machine, and whose machine passed a self-test. */
@Component
class HostsControlRoom implements ControlRoom.FunnelSource {

    private final JdbcClient jdbc;

    HostsControlRoom(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ControlRoom.Step> steps() {
        long linked = jdbc.sql("select count(distinct owner_user_id) from hosts.hosts")
                .query(Long.class)
                .single();
        long passed = jdbc.sql("""
                        select count(distinct h.owner_user_id) from hosts.hosts h
                        where exists (select 1 from hosts.self_test_results r where r.host_id = h.id and r.passed)
                        """).query(Long.class).single();
        return List.of(
                new ControlRoom.Step("hosts", 1, "linked a machine", linked),
                new ControlRoom.Step("hosts", 2, "self-test passed", passed));
    }

    @Override
    public List<ControlRoom.Reached> reached(java.time.Instant since) {
        var from = java.sql.Timestamp.from(since);
        var reached = new java.util.ArrayList<>(jdbc.sql("""
                        select owner_user_id, min(created_at) as first from hosts.hosts
                        where created_at >= :from group by owner_user_id
                        """)
                .param("from", from)
                .query((rs, n) -> new ControlRoom.Reached(
                        "hosts",
                        1,
                        "linked a machine",
                        rs.getObject("owner_user_id", java.util.UUID.class),
                        rs.getTimestamp("first").toInstant()))
                .list());
        reached.addAll(jdbc.sql("""
                        select h.owner_user_id, min(r.reported_at) as first
                        from hosts.self_test_results r join hosts.hosts h on h.id = r.host_id
                        where r.passed and r.reported_at >= :from group by h.owner_user_id
                        """)
                .param("from", from)
                .query((rs, n) -> new ControlRoom.Reached(
                        "hosts",
                        2,
                        "self-test passed",
                        rs.getObject("owner_user_id", java.util.UUID.class),
                        rs.getTimestamp("first").toInstant()))
                .list());
        return reached;
    }
}
