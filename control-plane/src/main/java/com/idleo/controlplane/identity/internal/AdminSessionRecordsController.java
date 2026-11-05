package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin sessions, as records (ADR 0051): an admin sees their own and can end any of them; the Owner sees everyone's and
 * can end any. {@code /admin/v1/sessions} is renters' sessions, so these live under {@code me} and {@code admins}.
 */
@RestController
@ConditionalOnWebApplication
class AdminSessionRecordsController {

    private final AdminSessions sessions;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final Clock clock;

    AdminSessionRecordsController(
            AdminSessions sessions, AuditTrail audit, TransactionTemplate transactions, JdbcClient jdbc, Clock clock) {
        this.sessions = sessions;
        this.audit = audit;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** {@code current} is the session that's asking. */
    record AdminSessionRecord(
            UUID id,
            UUID userId,
            Instant startedAt,
            @Nullable Instant lastUsedAt,
            Instant expiresAt,
            Instant endsBy,
            @Nullable String ip,
            @Nullable String userAgent,
            boolean current) {}

    record AdminSessionRecords(
            List<AdminSessionRecord> sessions, @Nullable String nextCursor) {}

    /** Everyone's live sessions, their orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("admin-sessions", AdminPermission.ADMINS_MANAGE, true)
            .select("id, user_id, created_at, last_used_at, expires_at, ends_by, host(ip) as address, user_agent")
            .from("identity.admin_sessions")
            .always("ended_at is null")
            .sort("startedAt", AdminListing.Type.TIME, "created_at")
            .sort("expiresAt", AdminListing.Type.TIME, "expires_at")
            .sortedBy("startedAt", true)
            .filter("liveAt", "expires_at > cast(:liveAt as timestamptz)")
            .csv("id", "id")
            .csv("userId", "user_id")
            .csv("startedAt", "created_at")
            .csv("lastUsedAt", "last_used_at")
            .csv("expiresAt", "expires_at")
            .csv("address", "host(ip)")
            .csv("userAgent", "user_agent")
            .build();

    @RequiresPermission(AdminPermission.SELF)
    @GetMapping("/admin/v1/me/sessions")
    AdminSessionRecords mine(@AuthenticationPrincipal CurrentUser admin) {
        return records(admin.id());
    }

    @RequiresPermission(AdminPermission.SELF)
    @PostMapping("/admin/v1/me/sessions/{id}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void endMine(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        end(admin, id, admin.id());
    }

    @RequiresPermission(AdminPermission.ADMINS_MANAGE)
    @AdminList("admin-sessions")
    @GetMapping("/admin/v1/admins/sessions")
    AdminSessionRecords everyones(
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(sort, "liveAt", Timestamp.from(Instant.now(clock))),
                cursor,
                limit,
                (rs, n) -> record(new AdminSessions.Record(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null
                                ? null
                                : rs.getTimestamp("last_used_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("ends_by").toInstant(),
                        null,
                        rs.getString("address"),
                        rs.getString("user_agent"))));
        return new AdminSessionRecords(page.rows(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.ADMINS_MANAGE)
    @PostMapping("/admin/v1/admins/sessions/{id}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void endAnyones(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        end(admin, id, null);
    }

    private AdminSessionRecords records(UUID userId) {
        return new AdminSessionRecords(
                sessions.live(userId).stream().map(this::record).toList(), null);
    }

    private AdminSessionRecord record(AdminSessions.Record session) {
        return new AdminSessionRecord(
                session.id(),
                session.userId(),
                session.startedAt(),
                session.lastUsedAt(),
                session.expiresAt(),
                session.endsBy(),
                session.ip(),
                session.userAgent(),
                session.id().equals(AdminRequestContext.adminSession()));
    }

    private void end(CurrentUser admin, UUID sessionId, @Nullable UUID owner) {
        transactions.executeWithoutResult(status -> {
            if (!sessions.endById(sessionId, owner, admin.id())) {
                throw ApiProblem.of(Problem.NOT_FOUND, "There's no such session.");
            }
            audit.record(new AuditTrail.Entry(
                    audit.admin(admin),
                    "admin_session.end",
                    "admin_session",
                    sessionId.toString(),
                    "ended from the console",
                    null,
                    Map.of("ended", true)));
        });
    }
}
