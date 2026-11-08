package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.identity.AdminAccess;
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
class AdminPermissions implements AdminAccess {

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

    @Override
    public Set<AdminPermission> permissions(UUID admin) {
        return of(admin);
    }

    @Override
    public List<Mentionable> mentionable() {
        return jdbc.sql("""
                        select a.user_id, coalesce(u.display_name, u.email, a.user_id::text) as name
                        from identity.admins a
                        join identity.users u on u.id = a.user_id
                        where a.locked_at is null and a.totp_confirmed_at is not null and u.status = 'active'
                        order by name
                        """)
                .query((rs, n) -> new Mentionable(rs.getObject("user_id", UUID.class), rs.getString("name")))
                .list();
    }

    boolean has(UUID userId, AdminPermission permission) {
        return permission == AdminPermission.SELF || of(userId).contains(permission);
    }

    /** Every admin who can use this permission now: enrolled, not locked out, on an active account. */
    List<UUID> holders(AdminPermission permission) {
        return jdbc.sql("""
                        select distinct g.user_id
                        from identity.admin_role_grants g
                        join identity.admin_role_permissions p on p.role_id = g.role_id
                        join identity.admins a on a.user_id = g.user_id
                        join identity.users u on u.id = g.user_id
                        where p.permission = :permission and a.locked_at is null and a.totp_confirmed_at is not null
                          and u.status = 'active'
                        """)
                .param("permission", permission.key())
                .query(UUID.class)
                .list();
    }

    @Override
    public List<UUID> owners() {
        return holdersOfRole("owner");
    }

    /** Every admin who holds this role and can use the console now. */
    List<UUID> holdersOfRole(String role) {
        return jdbc.sql("""
                        select g.user_id from identity.admin_role_grants g
                        join identity.admins a on a.user_id = g.user_id
                        join identity.users u on u.id = g.user_id
                        where g.role_id = :role and a.locked_at is null and a.totp_confirmed_at is not null
                          and u.status = 'active'
                        """).param("role", role).query(UUID.class).list();
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
