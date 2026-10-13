package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPages;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The audit log, newest first, for the admin console (ADR 0027). Read only: nothing can change a row. */
@RestController
@RequestMapping("/admin/v1/audit")
@ConditionalOnWebApplication
class AuditLogController {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditLogController(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * One action: who did it ({@code actorKind} {@code admin}, {@code operator}, {@code owner} or {@code system}), what
     * ({@code host.activate}), to what, why, and what changed.
     */
    record AuditEntry(
            UUID id,
            String actorKind,
            @Nullable UUID actorUserId,
            String actorName,
            String action,
            String targetType,
            String targetId,
            String reason,
            @Nullable Map<String, Object> before,
            @Nullable Map<String, Object> after,
            Instant createdAt) {}

    record AuditPage(List<AuditEntry> entries, @Nullable String nextCursor) {}

    /**
     * The log, filtered: {@code actor} is part of the actor's name or their user id, {@code action} an action or the
     * start of one ({@code host.} for every host action), {@code targetType} and {@code targetId} exact, and {@code
     * from} and {@code to} a time range.
     */
    @GetMapping
    AuditPage list(
            @RequestParam(required = false) @Nullable String actor,
            @RequestParam(required = false) @Nullable String action,
            @RequestParam(required = false) @Nullable String targetType,
            @RequestParam(required = false) @Nullable String targetId,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        var entries = jdbc.sql("""
                        select * from identity.audit_log
                        where (cast(:actor as text) is null
                               or actor_name ilike '%' || cast(:actor as text) || '%'
                               or actor_user_id::text = cast(:actor as text))
                          and (cast(:action as text) is null or action = cast(:action as text)
                               or (right(cast(:action as text), 1) = '.'
                                   and starts_with(action, cast(:action as text))))
                          and (cast(:targetType as text) is null or target_type = cast(:targetType as text))
                          and (cast(:targetId as text) is null or target_id = cast(:targetId as text))
                          and (cast(:from as timestamptz) is null or created_at >= cast(:from as timestamptz))
                          and (cast(:to as timestamptz) is null or created_at < cast(:to as timestamptz))
                          and (cast(:after as uuid) is null or id < cast(:after as uuid))
                        order by id desc
                        limit :limit
                        """)
                .param("actor", blankToNull(actor))
                .param("action", blankToNull(action))
                .param("targetType", blankToNull(targetType))
                .param("targetId", blankToNull(targetId))
                .param("from", from == null ? null : Timestamp.from(from))
                .param("to", to == null ? null : Timestamp.from(to))
                .param("after", AdminPages.after(cursor))
                .param("limit", size + 1)
                .query((rs, n) -> new AuditEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("actor_kind"),
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("reason"),
                        object(rs.getString("before")),
                        object(rs.getString("after")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        var page = entries.size() > size ? entries.subList(0, size) : entries;
        return new AuditPage(
                List.copyOf(page),
                entries.size() > size ? AdminPages.cursorAfter(page.getLast().id()) : null);
    }

    private @Nullable Map<String, Object> object(@Nullable String text) {
        return text == null ? null : json.readValue(text, OBJECT);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
