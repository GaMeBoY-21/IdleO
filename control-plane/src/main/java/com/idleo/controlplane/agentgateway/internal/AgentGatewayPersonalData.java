package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.hosts.HostOwners;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each of a person's machines' presence changes (ADR 0056): when it was online, kept 90 days. In their export under
 * hosting, and deleted with their records.
 */
@Component
class AgentGatewayPersonalData implements PersonalData {

    private final JdbcClient jdbc;
    private final HostOwners owners;

    AgentGatewayPersonalData(JdbcClient jdbc, HostOwners owners) {
        this.jdbc = jdbc;
        this.owners = owners;
    }

    @Override
    public String module() {
        return "agentgateway";
    }

    private List<UUID> hosts(UUID userId) {
        return owners.ownedBy(userId).stream().map(HostOwners.OwnedHost::id).toList();
    }

    @Override
    public List<Group> summary(UUID userId) {
        var hosts = hosts(userId);
        if (hosts.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select count(*) as n, min(at) as earliest, max(at) as latest
                        from agentgateway.presence_changes where host_id in (:hosts)
                        """)
                .param("hosts", hosts)
                .query((rs, n) -> rs.getLong("n") == 0
                        ? List.<Group>of()
                        : List.of(new Group(
                                "hosting",
                                "times a machine came online or went offline",
                                rs.getLong("n"),
                                instant(rs.getTimestamp("earliest")),
                                instant(rs.getTimestamp("latest")))))
                .single();
    }

    @Override
    public List<Part> export(UUID userId) {
        var hosts = hosts(userId);
        if (hosts.isEmpty()) {
            return List.of();
        }
        var changes = jdbc.sql("""
                        select host_id, state, at from agentgateway.presence_changes
                        where host_id in (:hosts) order by at
                        """)
                .param("hosts", hosts)
                .query((rs, n) -> Map.of(
                        "hostId", rs.getObject("host_id", UUID.class).toString(),
                        "state", rs.getString("state"),
                        "at", rs.getTimestamp("at").toInstant().toString()))
                .list();
        return changes.isEmpty() ? List.of() : List.of(new Part("hosting", "presenceChanges", changes));
    }

    @Override
    @Transactional
    public void erase(UUID userId, ErasureStep step) {
        var hosts = hosts(userId);
        if (step != ErasureStep.DELETE_RECORDS || hosts.isEmpty()) {
            return;
        }
        jdbc.sql("delete from agentgateway.presence_changes where host_id in (:hosts)")
                .param("hosts", hosts)
                .update();
        jdbc.sql("delete from agentgateway.presence_states where host_id in (:hosts)")
                .param("hosts", hosts)
                .update();
    }

    private static java.time.@org.jspecify.annotations.Nullable Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }
}
