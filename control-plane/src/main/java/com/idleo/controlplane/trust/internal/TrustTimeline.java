package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTimeline;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Trust's part of the console's timeline (ADR 0052): reports, and what abuse checks found. */
@Configuration(proxyBeanMethods = false)
class TrustTimeline {

    @Bean
    AdminTimeline reportsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("report", AdminPermission.DISPUTES_READ, (subject, window) -> {
            var column = switch (subject.kind()) {
                case "user" -> "renter_id";
                case "session" -> "session_id";
                default -> null;
            };
            if (column == null) {
                return List.of();
            }
            return jdbc.sql("select id, reason, state, created_at from trust.disputes where " + column + """
                             = :id and created_at <= :until
                            order by created_at desc
                            limit :limit
                            """)
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "report:" + rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            "report",
                            "Report: " + rs.getString("reason").replace('_', ' '),
                            rs.getString("state"),
                            "report",
                            rs.getString("id")))
                    .list();
        });
    }

    @Bean
    AdminTimeline abuseTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("abuse", AdminPermission.ABUSE_READ, (subject, window) -> {
            var column = switch (subject.kind()) {
                case "user" -> "renter_id";
                case "host" -> "host_id";
                case "session" -> "session_id";
                default -> null;
            };
            if (column == null) {
                return List.of();
            }
            return jdbc.sql("select id, action, state, created_at from trust.abuse_items where " + column + """
                             = :id and created_at <= :until
                            order by created_at desc
                            limit :limit
                            """)
                    .param("id", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "abuse:" + rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            "abuse",
                            "Abuse check: " + rs.getString("action"),
                            rs.getString("state"),
                            "abuse",
                            rs.getString("id")))
                    .list();
        });
    }
}
