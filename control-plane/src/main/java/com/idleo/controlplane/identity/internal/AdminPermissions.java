package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What each admin may do (ADR 0051): the one reader of the roles an admin holds and the permissions in them. Asked on
 * every admin request, so a change of roles counts from the next one. A permission a role names that this release
 * doesn't know is ignored: an old release beside a newer one's migration (ADR 0046).
 */
@Component
class AdminPermissions {

    private final JdbcClient jdbc;

    AdminPermissions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A role: its id, the name the console shows, and whether this admin holds it. */
    record Role(String id, String name) {}

    /** Everything this admin may do, always including {@link AdminPermission#SELF}. */
    Set<AdminPermission> of(UUID userId) {
        var held = EnumSet.of(AdminPermission.SELF);
        var known = EnumSet.allOf(AdminPermission.class);
        jdbc.sql("""
                        select distinct p.permission
                        from identity.admin_role_grants g
                        join identity.admin_role_permissions p on p.role_id = g.role_id
                        where g.user_id = :userId
                        """)
                .param("userId", userId)
                .query(String.class)
                .list()
                .forEach(key -> known.stream()
                        .filter(permission -> permission.key().equals(key))
                        .findFirst()
                        .ifPresent(held::add));
        return held;
    }

    boolean has(UUID userId, AdminPermission permission) {
        return permission == AdminPermission.SELF || of(userId).contains(permission);
    }

    /** The roles this admin holds, by name. */
    List<Role> rolesOf(UUID userId) {
        return jdbc.sql("""
                        select r.id, r.name from identity.admin_role_grants g
                        join identity.admin_roles r on r.id = g.role_id
                        where g.user_id = :userId
                        order by r.name
                        """)
                .param("userId", userId)
                .query((rs, n) -> new Role(rs.getString("id"), rs.getString("name")))
                .list();
    }
}
