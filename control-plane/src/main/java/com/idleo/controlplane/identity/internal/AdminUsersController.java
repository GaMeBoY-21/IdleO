package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
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

    private static final String SELECT_COLUMNS = """
            u.id, u.version, u.email, u.display_name, u.status, u.created_at,
                   (select max(e.last_sign_in_at) from identity.external_identities e where e.user_id = u.id)
                       as last_sign_in_at,
                   exists (select 1 from identity.admins a where a.user_id = u.id) as admin,
                   (select count(*) from identity.api_keys k where k.user_id = u.id and k.revoked_at is null)
                       as api_keys,
                   (select i.note from identity.invite_codes i where i.used_by = u.id) as invite_note,
                   u.suspension_cause
            """;

    private static final String SELECT = "select " + SELECT_COLUMNS + " from identity.users u ";

    /** A person's security log, its orders and CSV (ADR 0052). */
    static final AdminListing SECURITY_EVENTS = AdminListing.of("security-events", AdminPermission.USERS_READ, true)
            .noBulk("It's a record of what happened: nothing in it changes.")
            .select("id, kind, detail, host(address) as address, at")
            .from("identity.security_events")
            .sort("at", AdminListing.Type.UUID, "id")
            .sort("kind", AdminListing.Type.TEXT, "kind")
            .sortedBy("at", true)
            .filter("user", "user_id = cast(:user as uuid)")
            .csv("id", "id")
            .csv("userId", "user_id")
            .csv("kind", "kind")
            .csv("detail", "detail")
            .csv("address", "host(address)")
            .csv("at", "at")
            .build();

    private final JdbcClient jdbc;
    private final UserSuspensions suspensions;
    private final AuditTrail audit;
    private final AccessLog accessLog;
    private final AdminTagged tagged;
    private final Approvals approvals;

    AdminUsersController(
            JdbcClient jdbc,
            UserSuspensions suspensions,
            AuditTrail audit,
            AccessLog accessLog,
            Approvals approvals,
            AdminTagged tagged) {
        this.tagged = tagged;
        this.approvals = approvals;
        this.accessLog = accessLog;
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
            @Nullable String inviteNote,

            @Schema(allowableValues = {"host_misconduct", "other"}) @Nullable
            String suspensionCause,

            long version) {}

    /**
     * Suspending: why, in words for the audit log, and the cause, which decides whether the account's payouts are held:
     * {@code host_misconduct} holds them until the suspension is lifted, {@code other} (the default) leaves them being paid.
     */
    record SuspendRequest(
            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason,

            @Schema(allowableValues = {"host_misconduct", "other"}) @Nullable
            String cause) {

        SuspendRequest {
            reason = reason == null ? null : reason.strip();
        }
    }

    record AdminUserPage(List<AdminUser> users, @Nullable String nextCursor) {}

    /** The list, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("users", AdminPermission.USERS_READ, true)
            .bulk("user", "u.id", "u.id", "'User ' || right(cast(u.id as text), 8)", "tag", "warn")
            .select(SELECT_COLUMNS)
            .from("identity.users u")
            .id("u.id")
            .sort("createdAt", AdminListing.Type.UUID, "u.id")
            .sort("email", AdminListing.Type.TEXT, "coalesce(u.email, '')")
            .sort("name", AdminListing.Type.TEXT, "coalesce(u.display_name, '')")
            .sort("status", AdminListing.Type.TEXT, "u.status")
            .sortedBy("createdAt", true)
            .filter("query", """
                    u.email ilike '%' || cast(:query as text) || '%'
                    or u.display_name ilike '%' || cast(:query as text) || '%'
                    or u.id::text = lower(cast(:query as text))""")
            .filter("status", "u.status = cast(:status as text)")
            .filter("tagged", "u.id in (:tagged)")
            .csv("id", "u.id")
            .csv("email", "u.email")
            .csv("displayName", "u.display_name")
            .csv("status", "u.status")
            .csv("createdAt", "u.created_at")
            .build();

    /**
     * {@code query} is part of an email, a name, or a whole user id; {@code status} is waiting, active or suspended;
     * {@code sort} is createdAt, email, name or status, with {@code :asc} or {@code :desc}.
     */
    @RequiresPermission(AdminPermission.USERS_READ)
    @AdminList("users")
    @GetMapping
    AdminUserPage search(
            @RequestParam(required = false) @Nullable String query,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(
                        sort,
                        "query",
                        query == null ? null : query.strip(),
                        "status",
                        status == null ? null : status.strip(),
                        "tagged",
                        tagged.subjects("user", tag)),
                cursor,
                limit,
                AdminUsersController::user);
        return new AdminUserPage(page.rows(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.USERS_READ)
    @GetMapping("/{id}")
    AdminUser get(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        var user = find(id);
        accessLog.viewed(admin, id);
        return user;
    }

    /** Suspends an active account: its tokens and keys are refused from the next request. */
    @RequiresPermission(value = AdminPermission.USERS_SUSPEND, record = "user")
    @PostMapping("/{id}/suspend")
    AdminUser suspend(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody SuspendRequest request) {
        find(id);
        var cause = switch (request.cause() == null ? "other" : request.cause()) {
            case "host_misconduct" -> UserSuspensions.Cause.HOST_MISCONDUCT;
            case "other" -> UserSuspensions.Cause.OTHER;
            default -> throw ApiProblem.invalid("cause", "must be host_misconduct or other.");
        };
        refusing(() -> suspensions.suspend(id, request.reason(), audit.admin(admin), cause));
        return find(id);
    }

    /** Asks to reinstate a suspended account: it's lifting a suspension, so another admin approves it (ADR 0051). */
    @RequiresPermission(value = AdminPermission.USERS_UNSUSPEND, record = "user")
    @PostMapping("/{id}/unsuspend")
    ResponseEntity<Approvals.AdminApproval> unsuspend(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        var user = find(id);
        if (!"suspended".equals(user.status())) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This account isn't suspended.");
        }
        return approvals.submit(
                admin,
                "user.unsuspend",
                "user",
                id.toString(),
                Map.of("id", id),
                List.of(new Approvals.ApprovalChange("status", user.status(), "active")),
                request.reason());
    }

    /** One entry of a person's security log (ADR 0037): the only place an admin sees an address, masked. */
    record SecurityEvent(
            UUID id,
            String kind,
            @Nullable String detail,
            @Nullable String address,
            Instant at) {}

    record SecurityEventPage(
            List<SecurityEvent> events, @Nullable String nextCursor) {}

    /** The person's security log, newest first: sign-ins, failures they can be tied to, key and account events. */
    @RequiresPermission(AdminPermission.USERS_READ)
    @AdminList("security-events")
    @GetMapping("/{id}/security-events")
    SecurityEventPage securityEvents(
            @PathVariable UUID id,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        find(id);
        var page = SECURITY_EVENTS.page(
                jdbc,
                AdminListing.Asked.of(sort, "user", id),
                cursor,
                limit,
                (rs, n) -> new SecurityEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("detail"),
                        rs.getString("address"),
                        rs.getTimestamp("at").toInstant()));
        return new SecurityEventPage(page.rows(), page.nextCursor());
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
                rs.getString("invite_note"),
                rs.getString("suspension_cause"),
                rs.getLong("version"));
    }
}
