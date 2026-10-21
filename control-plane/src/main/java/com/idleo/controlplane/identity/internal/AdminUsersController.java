package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Users, for the admin console (ADR 0027): find them, read one, suspend and reinstate them. */
@RestController
@RequestMapping("/admin/v1/users")
@ConditionalOnWebApplication
class AdminUsersController {

    private static final String SELECT = """
            select u.id, u.email, u.display_name, u.status, u.created_at,
                   (select max(e.last_sign_in_at) from identity.external_identities e where e.user_id = u.id)
                       as last_sign_in_at,
                   exists (select 1 from identity.admins a where a.user_id = u.id) as admin,
                   (select count(*) from identity.api_keys k where k.user_id = u.id and k.revoked_at is null)
                       as api_keys,
                   (select i.note from identity.invite_codes i where i.used_by = u.id) as invite_note
            from identity.users u
            """;

    private final JdbcClient jdbc;
    private final UserSuspensions suspensions;
    private final AuditTrail audit;

    AdminUsersController(JdbcClient jdbc, UserSuspensions suspensions, AuditTrail audit) {
        this.jdbc = jdbc;
        this.suspensions = suspensions;
        this.audit = audit;
    }

    /**
     * A user as an admin sees them: their account, when they last signed in, whether they're an admin, how many API
     * keys they hold, and the note of the invite code that let them in.
     */
    record AdminUser(
            UUID id,
            @Nullable String email,
            @Nullable String displayName,
            String status,
            Instant createdAt,
            @Nullable Instant lastSignInAt,
            boolean admin,
            long apiKeys,
            @Nullable String inviteNote) {}

    record AdminUserPage(List<AdminUser> users, @Nullable String nextCursor) {}

    /** {@code query} is part of an email, a name, or a whole user id; {@code status} is waiting, active or suspended. */
    @GetMapping
    AdminUserPage search(
            @RequestParam(required = false) @Nullable String query,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        var text = query == null || query.isBlank() ? null : query.strip();
        var users = jdbc.sql(SELECT + """
                        where (cast(:query as text) is null
                               or u.email ilike '%' || cast(:query as text) || '%'
                               or u.display_name ilike '%' || cast(:query as text) || '%'
                               or u.id::text = lower(cast(:query as text)))
                          and (cast(:status as text) is null or u.status = cast(:status as text))
                          and (cast(:after as uuid) is null or u.id < cast(:after as uuid))
                        order by u.id desc
                        limit :limit
                        """)
                .param("query", text)
                .param("status", status == null || status.isBlank() ? null : status.strip())
                .param("after", AdminPages.after(cursor))
                .param("limit", size + 1)
                .query(AdminUsersController::user)
                .list();
        var page = users.size() > size ? users.subList(0, size) : users;
        return new AdminUserPage(
                List.copyOf(page),
                users.size() > size ? AdminPages.cursorAfter(page.getLast().id()) : null);
    }

    @GetMapping("/{id}")
    AdminUser get(@PathVariable UUID id) {
        return find(id);
    }

    /** Suspends an active account: its tokens and keys are refused from the next request. */
    @PostMapping("/{id}/suspend")
    AdminUser suspend(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        find(id);
        refusing(() -> suspensions.suspend(id, request.reason(), audit.admin(admin)));
        return find(id);
    }

    /** Reinstates a suspended account. */
    @PostMapping("/{id}/unsuspend")
    AdminUser unsuspend(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        find(id);
        refusing(() -> suspensions.unsuspend(id, request.reason(), audit.admin(admin)));
        return find(id);
    }

    /** One entry of a person's security log (ADR 0037): the only place an admin sees an address. */
    record SecurityEvent(
            String kind, @Nullable String detail, @Nullable String address, Instant at) {}

    record SecurityEventPage(
            List<SecurityEvent> events, @Nullable String nextCursor) {}

    /** The person's security log, newest first: sign-ins, failures they can be tied to, key and account events. */
    @GetMapping("/{id}/security-events")
    SecurityEventPage securityEvents(
            @PathVariable UUID id,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        find(id);
        int size = AdminPages.limit(limit);
        var rows = jdbc.sql("""
                        select id, kind, detail, host(address) as address, at from identity.security_events
                        where user_id = :user and (cast(:after as uuid) is null or id < cast(:after as uuid))
                        order by id desc
                        limit :limit
                        """)
                .param("user", id)
                .param("after", AdminPages.after(cursor))
                .param("limit", size + 1)
                .query((rs, n) -> Map.entry(
                        rs.getObject("id", UUID.class),
                        new SecurityEvent(
                                rs.getString("kind"),
                                rs.getString("detail"),
                                rs.getString("address"),
                                rs.getTimestamp("at").toInstant())))
                .list();
        var page = rows.size() > size ? rows.subList(0, size) : rows;
        return new SecurityEventPage(
                page.stream().map(Map.Entry::getValue).toList(),
                rows.size() > size ? AdminPages.cursorAfter(page.getLast().getKey()) : null);
    }

    private AdminUser find(UUID id) {
        return jdbc.sql(SELECT + " where u.id = :id")
                .param("id", id)
                .query(AdminUsersController::user)
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such user."));
    }

    private static void refusing(Runnable action) {
        try {
            action.run();
        } catch (UserSuspensions.Refused e) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, e.getMessage());
        }
    }

    private static AdminUser user(ResultSet rs, int row) throws SQLException {
        var lastSignIn = rs.getTimestamp("last_sign_in_at");
        return new AdminUser(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                lastSignIn == null ? null : lastSignIn.toInstant(),
                rs.getBoolean("admin"),
                rs.getLong("api_keys"),
                rs.getString("invite_note"));
    }
}
