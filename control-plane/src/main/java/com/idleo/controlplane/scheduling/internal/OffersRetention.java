package com.idleo.controlplane.scheduling.internal;

import com.idleo.controlplane.RetentionPeriods;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** How each session was matched is kept a year, then deleted (ADR 0037); an offer still waiting is never deleted. */
@Configuration(proxyBeanMethods = false)
class OffersRetention {

    @Bean
    RetentionRule offersRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                "offers",
                now -> jdbc.sql("delete from scheduling.offers where status <> 'pending' and created_at < :before")
                        .param("before", Timestamp.from(now.minus(periods.of("offers"))))
                        .update());
    }
}
