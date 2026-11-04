package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    AdminSessionRecordsController(AdminSessions sessions, AuditTrail audit, TransactionTemplate transactions) {
        this.sessions = sessions;
        this.audit = audit;
        this.transactions = transactions;
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

    record AdminSessionRecords(List<AdminSessionRecord> sessions) {}

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
    @GetMapping("/admin/v1/admins/sessions")
    AdminSessionRecords everyones() {
        return records(null);
    }

    @RequiresPermission(AdminPermission.ADMINS_MANAGE)
    @PostMapping("/admin/v1/admins/sessions/{id}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void endAnyones(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        end(admin, id, null);
    }

    private AdminSessionRecords records(@Nullable UUID userId) {
        var current = AdminRequestContext.adminSession();
        return new AdminSessionRecords(sessions.live(userId).stream()
                .map(session -> new AdminSessionRecord(
                        session.id(),
                        session.userId(),
                        session.startedAt(),
                        session.lastUsedAt(),
                        session.expiresAt(),
                        session.endsBy(),
                        session.ip(),
                        session.userAgent(),
                        session.id().equals(current)))
                .toList());
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
