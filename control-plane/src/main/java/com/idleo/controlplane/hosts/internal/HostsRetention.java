package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.RetentionPeriods;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The hosts module's retention rules (ADR 0037): links to add a machine once expired (they name the computer), and
 * setup's measurements.
 */
@Configuration(proxyBeanMethods = false)
class HostsRetention {

    @Bean
    RetentionRule hostLinksRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                "host-links",
                now -> jdbc.sql("delete from hosts.host_links where expires_at < :before")
                        .param("before", Timestamp.from(now.minus(periods.of("host-links"))))
                        .update());
    }

    @Bean
    RetentionRule installEventsRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                "install-events",
                now -> jdbc.sql("delete from hosts.install_events where received_at < :before")
                        .param("before", Timestamp.from(now.minus(periods.of("install-events"))))
                        .update());
    }
}
