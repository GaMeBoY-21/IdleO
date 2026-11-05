package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTimeline;
import com.idleo.controlplane.Rupees;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Payouts' part of the console's timeline (ADR 0052): what was sent to a person. */
@Configuration(proxyBeanMethods = false)
class PayoutsTimeline {

    @Bean
    AdminTimeline payoutEventsTimeline(JdbcClient jdbc) {
        return AdminTimeline.of("payout", AdminPermission.PAYOUTS_READ, (subject, window) -> {
            if (!subject.is("user")) {
                return List.of();
            }
            return jdbc.sql("""
                            select id, net_paise, status, created_at from payouts.payouts
                            where user_id = :user and created_at <= :until
                            order by created_at desc
                            limit :limit
                            """)
                    .param("user", subject.id())
                    .param("until", Timestamp.from(window.until()))
                    .param("limit", window.limit())
                    .query((rs, n) -> new AdminTimeline.TimelineEvent(
                            "payout:" + rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            "payout",
                            "Payout of " + Rupees.format(rs.getLong("net_paise")),
                            rs.getString("status"),
                            "payout",
                            rs.getString("id")))
                    .list();
        });
    }
}
