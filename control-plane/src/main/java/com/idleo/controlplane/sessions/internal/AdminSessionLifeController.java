package com.idleo.controlplane.sessions.internal;

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

/** What a session's page reads of its life (ADR 0052): each attempt with its times, and how far its lease reached. */
@RestController
@ConditionalOnWebApplication
class AdminSessionLifeController {

    private final JdbcClient jdbc;

    AdminSessionLifeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One try on one host: when it was offered, told to start, ran and ended; and how far its lease was renewed. */
    record AdminSessionAttemptLife(
            int attempt,
            UUID hostId,
            Instant matchedAt,
            @Nullable Instant startDeliveredAt,
            @Nullable Instant runningAt,
            @Nullable Instant stopRequestedAt,
            @Nullable String stopReason,
            @Nullable Instant endedAt,
            @Nullable String lostReason,
            @Nullable Instant leaseCoveredFrom,
            @Nullable Instant leaseCoveredUntil,
            int leaseStretches) {}

    record AdminSessionLife(List<AdminSessionAttemptLife> attempts) {}

    @RequiresPermission(AdminPermission.SESSIONS_READ)
    @GetMapping("/admin/v1/sessions/{id}/life")
    AdminSessionLife life(@PathVariable UUID id) {
        return new AdminSessionLife(jdbc.sql("""
                        select a.*,
                               (select min(c.covered_from) from sessions.lease_coverage c
                                 where c.session_id = a.session_id and c.attempt = a.attempt) covered_from,
                               (select max(c.covered_until) from sessions.lease_coverage c
                                 where c.session_id = a.session_id and c.attempt = a.attempt) covered_until,
                               (select count(*) from sessions.lease_coverage c
                                 where c.session_id = a.session_id and c.attempt = a.attempt) stretches
                        from sessions.attempts a
                        where a.session_id = :id
                        order by a.attempt
                        """)
                .param("id", id)
                .query((rs, n) -> new AdminSessionAttemptLife(
                        rs.getInt("attempt"),
                        rs.getObject("host_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        instant(rs.getTimestamp("start_delivered_at")),
                        instant(rs.getTimestamp("running_at")),
                        instant(rs.getTimestamp("stop_requested_at")),
                        rs.getString("stop_reason"),
                        instant(rs.getTimestamp("ended_at")),
                        rs.getString("lost_reason"),
                        instant(rs.getTimestamp("covered_from")),
                        instant(rs.getTimestamp("covered_until")),
                        rs.getInt("stretches")))
                .list());
    }

    private static @Nullable Instant instant(java.sql.@Nullable Timestamp at) {
        return at == null ? null : at.toInstant();
    }
}
