package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.RequiresPermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** What a session's page reads of what agents were told (ADR 0052): each command by its type, never its payload. */
@RestController
@ConditionalOnWebApplication
class AdminAgentCommandsController {

    private final JdbcClient jdbc;

    AdminAgentCommandsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record AdminAgentCommand(
            UUID id,
            UUID hostId,
            String type,
            String status,
            int attempts,
            Instant createdAt,
            @Nullable Instant sentAt,
            @Nullable Instant ackedAt) {}

    record AdminAgentCommands(List<AdminAgentCommand> commands) {}

    @RequiresPermission(AdminPermission.SESSIONS_READ)
    @GetMapping("/admin/v1/sessions/{id}/commands")
    AdminAgentCommands commands(@PathVariable UUID id) {
        return new AdminAgentCommands(jdbc.sql("""
                        select id, host_id, type, status, attempts, created_at, sent_at, acked_at
                        from agentgateway.agent_commands
                        where session_id = :id
                        order by created_at, id
                        limit 500
                        """)
                .param("id", id)
                .query((rs, n) -> new AdminAgentCommand(
                        rs.getObject("id", UUID.class),
                        rs.getObject("host_id", UUID.class),
                        rs.getString("type"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("sent_at") == null
                                ? null
                                : rs.getTimestamp("sent_at").toInstant(),
                        rs.getTimestamp("acked_at") == null
                                ? null
                                : rs.getTimestamp("acked_at").toInstant()))
                .list());
    }
}
