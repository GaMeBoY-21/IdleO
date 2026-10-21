package com.idleo.controlplane;

import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The shared kernel's retention rules (ADR 0037): a durable event between modules once it's been delivered, and a
 * create request's Idempotency-Key once it has expired, in every module's {@code idempotency_keys} table (their
 * one writer is {@link IdempotentRequests}).
 */
@Configuration(proxyBeanMethods = false)
class SharedRetention {

    @Bean
    RetentionRule eventPublicationsRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                "event-publications",
                now -> jdbc.sql("delete from modulith.event_publication where completion_date < :before")
                        .param("before", Timestamp.from(now.minus(periods.of("event-publications"))))
                        .update());
    }

    @Bean
    RetentionRule idempotencyKeysRule(JdbcClient jdbc) {
        return RetentionRule.of("idempotency-keys", now -> {
            long deleted = 0;
            for (var table : jdbc.sql("""
                            select table_schema || '.' || table_name from information_schema.tables
                            where table_name = 'idempotency_keys' order by 1
                            """).query(String.class).list()) {
                deleted += jdbc.sql("delete from " + table + " where expires_at < :now and state = 'completed'")
                        .param("now", Timestamp.from(now))
                        .update();
            }
            return deleted;
        });
    }
}
