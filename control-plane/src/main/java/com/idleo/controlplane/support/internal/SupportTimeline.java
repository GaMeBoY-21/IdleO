package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTimeline;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Support's part of the console's timeline (ADR 0052): tickets from a person, or about a session or a host. */
@Configuration(proxyBeanMethods = false)
class SupportTimeline {

    @Bean
    AdminTimeline ticketsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("ticket", AdminPermission.TICKETS_READ, (subject, window) -> {
            var column = switch (subject.kind()) {
                case "user" -> "user_id";
                case "session" -> "session_id";
                case "host" -> "host_id";
                default -> null;
            };
            if (column == null) {
                return List.of();
            }
            return jdbc.sql("select id, number, topic, status, created_at from support.tickets where " + column + """
                             = :id and created_at <= :until
                            order by created_at desc
                            limit :limit
                            """)
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "ticket:" + rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            "ticket",
                            "Ticket #" + rs.getLong("number") + " · " + rs.getString("topic"),
                            rs.getString("status"),
                            "ticket",
                            rs.getString("id")))
                    .list();
        });
    }
}
