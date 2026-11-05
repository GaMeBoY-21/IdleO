package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.WorkQueue;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What trust has waiting (ADR 0053): abuse flags, the sooner when their session is still running, and reports. */
@Configuration(proxyBeanMethods = false)
class TrustWorkQueues {

    @Bean
    WorkQueue abuseQueue(JdbcClient jdbc) {
        return queue(
                "abuse",
                AdminPermission.ABUSE_READ,
                () -> jdbc.sql("""
                        select a.id, a.session_id, a.renter_id, a.created_at, a.updated_at,
                               not exists (select 1 from trust.ended_sessions e where e.session_id = a.session_id) as running
                        from trust.abuse_items a where a.state = 'open'
                        """)
                        .query((rs, n) -> {
                            var id = rs.getObject("id", UUID.class);
                            var running = rs.getBoolean("running");
                            return new WorkQueue.Item(
                                    id.toString(),
                                    "session",
                                    rs.getObject("session_id", UUID.class),
                                    rs.getObject("renter_id", UUID.class),
                                    running ? "Abuse flag on a running session" : "Abuse flag",
                                    "/admin/abuse/?id=" + id,
                                    running ? "abuse-running" : "abuse",
                                    rs.getTimestamp("created_at").toInstant(),
                                    null,
                                    false,
                                    null,
                                    rs.getTimestamp("updated_at").toInstant(),
                                    0,
                                    running);
                        })
                        .list());
    }

    @Bean
    WorkQueue reportQueue(JdbcClient jdbc) {
        return queue(
                "report",
                AdminPermission.DISPUTES_READ,
                () -> jdbc.sql("""
                        select id, session_id, renter_id, reason, created_at from trust.disputes where state = 'open'
                        """)
                        .query((rs, n) -> {
                            var id = rs.getObject("id", UUID.class);
                            return new WorkQueue.Item(
                                    id.toString(),
                                    "session",
                                    rs.getObject("session_id", UUID.class),
                                    rs.getObject("renter_id", UUID.class),
                                    "Report: " + rs.getString("reason").replace('_', ' '),
                                    "/admin/disputes/?id=" + id,
                                    "report",
                                    rs.getTimestamp("created_at").toInstant(),
                                    null,
                                    false,
                                    null,
                                    null,
                                    0,
                                    false);
                        })
                        .list());
    }

    @Bean
    WorkQueue appealQueue(JdbcClient jdbc) {
        return queue(
                "appeal",
                AdminPermission.APPEALS_DECIDE,
                () -> jdbc.sql("""
                        select id, user_id, created_at from trust.appeals where state = 'open'
                        """)
                        .query((rs, n) -> {
                            var id = rs.getObject("id", UUID.class);
                            var user = rs.getObject("user_id", UUID.class);
                            return new WorkQueue.Item(
                                    id.toString(),
                                    "user",
                                    user,
                                    user,
                                    "An appeal",
                                    "/admin/appeals/?id=" + id,
                                    "appeal",
                                    rs.getTimestamp("created_at").toInstant(),
                                    null,
                                    false,
                                    null,
                                    null,
                                    0,
                                    false);
                        })
                        .list());
    }

    private static WorkQueue queue(
            String type, AdminPermission permission, java.util.function.Supplier<List<WorkQueue.Item>> open) {
        return new WorkQueue() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public AdminPermission permission() {
                return permission;
            }

            @Override
            public List<Item> open() {
                return open.get();
            }
        };
    }
}
