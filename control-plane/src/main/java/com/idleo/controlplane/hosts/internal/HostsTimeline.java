package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTimeline;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Hosts' part of the console's timeline (ADR 0052): a host's changes of status, and a person's hosts. */
@Configuration(proxyBeanMethods = false)
class HostsTimeline {

    @Bean
    AdminTimeline hostStatusTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("host_status", AdminPermission.HOSTS_READ, (subject, window) -> {
            if (!subject.is("host")) {
                return List.of();
            }
            return jdbc.sql("""
                            select id, to_status, reason, changed_by, created_at from hosts.status_changes
                            where host_id = :id and created_at <= :until
                            order by created_at desc
                            limit :limit
                            """)
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "host_status:" + rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            "host_status",
                            "Now " + rs.getString("to_status"),
                            rs.getString("reason"),
                            null,
                            null))
                    .list();
        });
    }

    @Bean
    AdminTimeline ownedHostsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("host", AdminPermission.HOSTS_READ, (subject, window) -> {
            if (!subject.is("user")) {
                return List.of();
            }
            return jdbc.sql("""
                            select id, name, status, created_at from hosts.hosts
                            where owner_user_id = :id and created_at <= :until
                            order by created_at desc
                            limit :limit
                            """)
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "host:" + rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            "host",
                            "Linked " + rs.getString("name"),
                            rs.getString("status"),
                            "host",
                            rs.getString("id")))
                    .list();
        });
    }
}
