package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTimeline;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Sessions' part of the console's timeline (ADR 0052): those a person booked or a host ran, and each one's own steps. */
@Configuration(proxyBeanMethods = false)
class SessionsTimeline {

    @Bean
    AdminTimeline bookedSessionsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("session", AdminPermission.SESSIONS_READ, (subject, window) -> {
            var column = switch (subject.kind()) {
                case "user" -> "renter_id";
                case "host" -> "host_id";
                default -> null;
            };
            if (column == null) {
                return List.of();
            }
            return jdbc.sql("select id, template_id, state, created_at from sessions.sessions where " + column + """
                             = :id and created_at <= :until
                            order by created_at desc
                            limit :limit
                            """)
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "session:" + rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            "session",
                            "Session of " + rs.getString("template_id"),
                            rs.getString("state"),
                            "session",
                            rs.getString("id")))
                    .list();
        });
    }

    @Bean
    AdminTimeline sessionStepsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("session_step", AdminPermission.SESSIONS_READ, (subject, window) -> {
            if (!subject.is("session")) {
                return List.of();
            }
            return jdbc.sql("""
                            select id, to_state, reason, at from sessions.session_events
                            where session_id = :id and at <= :until
                            order by at desc
                            limit :limit
                            """)
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "session_step:" + rs.getString("id"),
                            rs.getTimestamp("at").toInstant(),
                            "session_step",
                            rs.getString("to_state"),
                            rs.getString("reason"),
                            null,
                            null))
                    .list();
        });
    }
}
