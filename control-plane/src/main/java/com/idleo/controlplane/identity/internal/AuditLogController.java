package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.RequiresPermission;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log, newest first, for the admin console (ADR 0027, ADR 0051): searched, checked and exported. Nothing can
 * change a row.
 */
@RestController
@RequestMapping("/admin/v1/audit")
@ConditionalOnWebApplication
class AuditLogController {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    /**
     * The log, its filters, orders and CSV (ADR 0052). Its file holds addresses only masked, and goes under {@code
     * export.create} without a second admin (ADR 0051): an auditor's reading of the log doesn't wait on the people in it.
     */
    static final AdminListing LIST = AdminListing.of("audit", AdminPermission.AUDIT_READ, false)
            .select("a.*, coalesce(u.email, a.actor_name) as shown_name, host(a.ip) as address")
            .from("identity.audit_log a left join identity.users u on u.id = a.actor_user_id")
            .id("a.id")
            .sort("createdAt", AdminListing.Type.UUID, "a.id")
            .sort("action", AdminListing.Type.TEXT, "a.action")
            .sortedBy("createdAt", true)
            .filter("actor", """
                    a.actor_name ilike '%' || cast(:actor as text) || '%'
                    or u.email ilike '%' || cast(:actor as text) || '%'
                    or a.actor_user_id::text = cast(:actor as text)""")
            .filter("action", """
                    a.action = cast(:action as text)
                    or (right(cast(:action as text), 1) = '.' and starts_with(a.action, cast(:action as text)))""")
            .filter("targetType", "a.target_type = cast(:targetType as text)")
            .filter("targetId", "a.target_id = cast(:targetId as text)")
            .filter("approvalId", "a.approval_id = cast(:approvalId as uuid)")
            .filter("requestId", "a.request_id = cast(:requestId as text)")
            .filter("from", "a.created_at >= cast(:from as timestamptz)")
            .filter("to", "a.created_at < cast(:to as timestamptz)")
            .csv("seq", "a.seq")
            .csv("id", "a.id")
            .csv("createdAt", "a.created_at")
            .csv("actorKind", "a.actor_kind")
            .csv("actorUserId", "a.actor_user_id")
            .csv("action", "a.action")
            .csv("targetType", "a.target_type")
            .csv("targetId", "a.target_id")
            .csv("reason", "a.reason")
            .csv("requestId", "a.request_id")
            .csv("adminSessionId", "a.admin_session_id")
            .csv("address", "host(a.ip)")
            .csv("approvalId", "a.approval_id")
            .build();

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditChain chain;
    private final AuditOffsiteCopies copies;

    AuditLogController(JdbcClient jdbc, JsonMapper json, AuditChain chain, AuditOffsiteCopies copies) {
        this.jdbc = jdbc;
        this.json = json;
        this.chain = chain;
        this.copies = copies;
    }

    /**
     * One action: who did it ({@code actorKind} {@code admin}, {@code operator}, {@code owner} or {@code system}), what
     * ({@code host.activate}), to what, why, and what changed. The log keeps an admin or an owner by id only (ADR
     * 0037); {@code actorName} is their email as the account has it now, or the id once the account is erased. Where it
     * came from: the request, the admin session, the address and browser, and the approval it ran under, if any.
     *
     * @param seq its place in the hash chain
     */
    record AuditEntry(
            UUID id,
            long seq,
            String actorKind,
            @Nullable UUID actorUserId,
            String actorName,
            String action,
            String targetType,
            String targetId,
            String reason,
            @Nullable Map<String, Object> before,
            @Nullable Map<String, Object> after,
            Instant createdAt,
            @Nullable String requestId,
            @Nullable UUID adminSessionId,
            @Nullable String ip,
            @Nullable String userAgent,
            @Nullable UUID approvalId) {}

    record AuditPage(List<AuditEntry> entries, @Nullable String nextCursor) {}

    /** One check of the chain: how many rows and how long; when broken, the first row that doesn't add up, and how. */
    record AuditChainCheck(
            long rows,
            long headSeq,
            boolean intact,
            @Nullable Long brokenSeq,
            @Nullable String detail,
            long tookMs,
            String trigger,
            Instant checkedAt) {

        static AuditChainCheck of(AuditChain.Check check) {
            return new AuditChainCheck(
                    check.rows(),
                    check.headSeq(),
                    check.intact(),
                    check.brokenSeq(),
                    check.detail(),
                    check.tookMs(),
                    check.trigger(),
                    check.checkedAt());
        }
    }

    /** One day's off-site copy. */
    record AuditCopy(LocalDate day, long rows, long lastSeq, String headHash, String objectKey, Instant writtenAt) {}

    /** How the log stands: its latest check, and its latest off-site copies, newest first. */
    record AuditStanding(@Nullable AuditChainCheck latestCheck, List<AuditCopy> copies) {}

    /**
     * The log, filtered: {@code actor} is part of the actor's name or their user id, {@code action} an action or the
     * start of one ({@code host.} for every host action), {@code targetType}, {@code targetId}, {@code approvalId} and
     * {@code requestId} exact, and {@code from} and {@code to} a time range.
     */
    @RequiresPermission(AdminPermission.AUDIT_READ)
    @AdminList("audit")
    @GetMapping
    AuditPage list(
            @RequestParam(required = false) @Nullable String actor,
            @RequestParam(required = false) @Nullable String action,
            @RequestParam(required = false) @Nullable String targetType,
            @RequestParam(required = false) @Nullable String targetId,
            @RequestParam(required = false) @Nullable UUID approvalId,
            @RequestParam(required = false) @Nullable String requestId,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(
                        sort,
                        "actor",
                        actor,
                        "action",
                        action,
                        "targetType",
                        targetType,
                        "targetId",
                        targetId,
                        "approvalId",
                        approvalId,
                        "requestId",
                        requestId,
                        "from",
                        from == null ? null : Timestamp.from(from),
                        "to",
                        to == null ? null : Timestamp.from(to)),
                cursor,
                limit,
                this::entry);
        return new AuditPage(page.rows(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.AUDIT_READ)
    @GetMapping("/standing")
    AuditStanding standing() {
        return new AuditStanding(
                chain.newest().map(AuditChainCheck::of).orElse(null),
                copies.latest(14).stream()
                        .map(copy -> new AuditCopy(
                                copy.day(),
                                copy.rows(),
                                copy.lastSeq(),
                                copy.headHash(),
                                copy.objectKey(),
                                copy.writtenAt()))
                        .toList());
    }

    /** Walks the whole chain now. A break also pages. */
    @RequiresPermission(AdminPermission.AUDIT_READ)
    @PostMapping("/verify")
    AuditChainCheck verify() {
        return AuditChainCheck.of(chain.verify("admin"));
    }

    private AuditEntry entry(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                rs.getString("actor_kind"),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("shown_name"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("reason"),
                object(rs.getString("before")),
                object(rs.getString("after")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("request_id"),
                rs.getObject("admin_session_id", UUID.class),
                rs.getString("address"),
                rs.getString("user_agent"),
                rs.getObject("approval_id", UUID.class));
    }

    private @Nullable Map<String, Object> object(@Nullable String text) {
        return text == null ? null : json.readValue(text, OBJECT);
    }
}
