package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who holds which role (ADR 0051). Making someone an admin stays an operator command; giving an admin a role or taking one
 * away is an Owner's, and another admin approves it.
 */
@RestController
@ConditionalOnWebApplication
class AdminRolesController {

    private final JdbcClient jdbc;
    private final AdminPermissions permissions;
    private final Approvals approvals;

    AdminRolesController(JdbcClient jdbc, AdminPermissions permissions, Approvals approvals) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.approvals = approvals;
    }

    /** An admin: who, whether they can use the console now, and their roles. */
    record AdminAccount(
            UUID id, @Nullable String email, boolean enrolled, boolean locked, List<String> roles, long version) {}

    /** A role and what it may do. */
    record AdminRole(String id, String name, List<String> permissions) {}

    record AdminAccounts(
            List<AdminAccount> admins,
            List<AdminRole> roles,
            @Nullable String nextCursor) {}

    /** The admins, their orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("admins", AdminPermission.ADMINS_MANAGE, true)
            .noBulk("Who can do what is decided one person at a time.")
            .select("a.user_id, u.email, a.totp_confirmed_at is not null as enrolled,"
                    + " a.locked_at is not null as locked, a.version")
            .from("identity.admins a join identity.users u on u.id = a.user_id")
            .id("a.user_id")
            .sort("grantedAt", AdminListing.Type.TIME, "a.granted_at")
            .sort("email", AdminListing.Type.TEXT, "coalesce(u.email, '')")
            .sortedBy("grantedAt", false)
            .csv("userId", "a.user_id")
            .csv("email", "u.email")
            .csv("enrolled", "a.totp_confirmed_at is not null")
            .csv("locked", "a.locked_at is not null")
            .csv("grantedAt", "a.granted_at")
            .build();

    @RequiresPermission(AdminPermission.ADMINS_MANAGE)
    @AdminList("admins")
    @GetMapping("/admin/v1/admins")
    AdminAccounts admins(
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = LIST.page(jdbc, AdminListing.Asked.of(sort), cursor, limit, (rs, n) -> {
            var id = rs.getObject("user_id", UUID.class);
            return new AdminAccount(
                    id,
                    rs.getString("email"),
                    rs.getBoolean("enrolled"),
                    rs.getBoolean("locked"),
                    permissions.rolesOf(id).stream()
                            .map(AdminPermissions.Role::id)
                            .toList(),
                    rs.getLong("version"));
        });
        var roles = jdbc.sql("select id, name from identity.admin_roles order by name")
                .query((rs, n) -> {
                    var id = rs.getString("id");
                    return new AdminRole(
                            id,
                            rs.getString("name"),
                            jdbc.sql("""
                                            select permission from identity.admin_role_permissions
                                            where role_id = :role order by permission
                                            """).param("role", id).query(String.class).list());
                })
                .list();
        return new AdminAccounts(page.rows(), roles, page.nextCursor());
    }

    /** Asks to give this admin a role. It ends their sessions when it runs. */
    @RequiresPermission(value = AdminPermission.ADMINS_MANAGE, record = "admin")
    @PostMapping("/admin/v1/admins/{id}/roles/{role}/grant")
    ResponseEntity<Approvals.AdminApproval> grant(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @PathVariable String role,
            @Valid @RequestBody AdminReason request) {
        return ask(admin, id, role, IdentityGatedActions.ROLE_GRANT, null, role, request);
    }

    /** Asks to take a role from this admin. It ends their sessions when it runs. */
    @RequiresPermission(value = AdminPermission.ADMINS_MANAGE, record = "admin")
    @PostMapping("/admin/v1/admins/{id}/roles/{role}/remove")
    ResponseEntity<Approvals.AdminApproval> remove(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @PathVariable String role,
            @Valid @RequestBody AdminReason request) {
        return ask(admin, id, role, IdentityGatedActions.ROLE_REMOVE, role, null, request);
    }

    private ResponseEntity<Approvals.AdminApproval> ask(
            CurrentUser admin,
            UUID id,
            String role,
            String kind,
            @Nullable String from,
            @Nullable String to,
            AdminReason request) {
        var known = jdbc.sql("select count(*) from identity.admin_roles where id = :role")
                .param("role", role)
                .query(Integer.class)
                .single();
        if (known == 0) {
            throw ApiProblem.of(Problem.NOT_FOUND, "There's no such role.");
        }
        return approvals.submit(
                admin,
                kind,
                "admin",
                id.toString(),
                Map.of("id", id, "role", role),
                List.of(new Approvals.ApprovalChange("role of admin " + id, from, to)),
                request.reason());
    }
}
