package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTimeline;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Identity's part of the console's timeline (ADR 0052): a person's sign-ins and security events, and admin actions. */
@Configuration(proxyBeanMethods = false)
class IdentityTimeline {

    @Bean
    AdminTimeline securityEventsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("security", AdminPermission.USERS_READ, (subject, window) -> {
            if (!subject.is("user")) {
                return List.of();
            }
            return jdbc.sql("""
                            select id, kind, detail, at from identity.security_events
                            where user_id = :user and at <= :until
                            order by at desc
                            limit :limit
                            """)
                    .param("user", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "security:" + rs.getString("id"),
                            rs.getTimestamp("at").toInstant(),
                            "security",
                            rs.getString("kind").replace('_', ' '),
                            rs.getString("detail"),
                            null,
                            null))
                    .list();
        });
    }

    /** What admins did to this thing: one row of the audit log each, without what it changed, which the log itself has. */
    @Bean
    AdminTimeline adminActionsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of(
                "admin_action",
                AdminPermission.AUDIT_READ,
                (subject, window) -> jdbc.sql("""
                        select id, action, actor_name, reason, created_at from identity.audit_log
                        where target_type = :type and target_id = :id and created_at <= :until
                        order by created_at desc
                        limit :limit
                        """)
                        .param("type", subject.kind())
                        .param("id", subject.id().toString())
                        .param("until", Timestamp.from(window.until()))
                        .param("limit", window.limit())
                        .query((rs, n) -> action(rs))
                        .list());
    }

    private static AdminTimeline.TimelineEvent action(ResultSet rs) throws SQLException {
        return new AdminTimeline.TimelineEvent(
                "admin_action:" + rs.getString("id"),
                rs.getTimestamp("created_at").toInstant(),
                "admin_action",
                rs.getString("action"),
                rs.getString("actor_name") + ": " + rs.getString("reason"),
                null,
                null);
    }
}
