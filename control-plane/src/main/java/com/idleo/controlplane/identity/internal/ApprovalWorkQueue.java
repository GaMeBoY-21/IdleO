package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.WorkQueue;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Requests waiting for a second admin (ADR 0051, ADR 0053): timed by when they lapse. */
@Component
class ApprovalWorkQueue implements WorkQueue {

    private final JdbcClient jdbc;

    ApprovalWorkQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String type() {
        return "approval";
    }

    @Override
    public AdminPermission permission() {
        return AdminPermission.APPROVALS_READ;
    }

    @Override
    public List<Item> open() {
        return jdbc.sql(
                        "select id, kind, created_at, expires_at from identity.approval_requests where state = 'pending'")
                .query((rs, n) -> new Item(
                        rs.getObject("id", UUID.class).toString(),
                        null,
                        null,
                        null,
                        "To approve: " + rs.getString("kind"),
                        "/admin/approvals/",
                        "approval",
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        false,
                        null,
                        null,
                        0,
                        false))
                .list();
    }
}
