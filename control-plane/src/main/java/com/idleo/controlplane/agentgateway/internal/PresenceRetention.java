package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.RetentionPeriods;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Each host's presence changes are kept 90 days (ADR 0056): a host is a person's machine. */
@Configuration(proxyBeanMethods = false)
class PresenceRetention {

    @Bean
    RetentionRule presenceChangesRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                "presence-changes",
                now -> jdbc.sql("delete from agentgateway.presence_changes where at < :before")
                        .param("before", Timestamp.from(now.minus(periods.of("presence-changes"))))
                        .update());
    }
}
