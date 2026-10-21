package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.RetentionPeriods;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What each person was told is kept a year, then deleted (ADR 0037); one whose email is still to be sent stays. */
@Configuration(proxyBeanMethods = false)
class NotificationsRetention {

    @Bean
    RetentionRule notificationsRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                "notifications",
                now -> jdbc.sql("""
                                delete from notifications.notifications
                                where created_at < :before and (not email_wanted or email_sent_at is not null)
                                """)
                        .param("before", Timestamp.from(now.minus(periods.of("notifications"))))
                        .update());
    }
}
