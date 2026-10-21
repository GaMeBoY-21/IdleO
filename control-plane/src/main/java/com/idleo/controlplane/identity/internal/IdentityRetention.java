package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.RetentionPeriods;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The identity module's retention rules (ADR 0037, docs/reference/personal-data-map.md), each over its own period in {@code
 * retention.periods}: device sign-ins once expired, invite codes never used once expired, the tries at an invite code
 * or the admin console's code, and ended admin console sessions. The security log's is {@link SecurityEventsRetention}.
 */
@Configuration(proxyBeanMethods = false)
class IdentityRetention {

    private static RetentionRule rule(String name, String sql, JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                name,
                now -> jdbc.sql(sql)
                        .param("before", Timestamp.from(now.minus(periods.of(name))))
                        .update());
    }

    @Bean
    RetentionRule cliLoginsRule(JdbcClient jdbc, RetentionPeriods periods) {
        return rule("cli-logins", "delete from identity.cli_logins where expires_at < :before", jdbc, periods);
    }

    @Bean
    RetentionRule inviteCodesRule(JdbcClient jdbc, RetentionPeriods periods) {
        return rule(
                "invite-codes",
                "delete from identity.invite_codes where used_by is null and expires_at < :before",
                jdbc,
                periods);
    }

    @Bean
    RetentionRule activationAttemptsRule(JdbcClient jdbc, RetentionPeriods periods) {
        return rule(
                "activation-attempts",
                "delete from identity.activation_attempts where attempted_at < :before",
                jdbc,
                periods);
    }

    @Bean
    RetentionRule adminCodeAttemptsRule(JdbcClient jdbc, RetentionPeriods periods) {
        return rule(
                "admin-code-attempts",
                "delete from identity.admin_code_attempts where attempted_at < :before",
                jdbc,
                periods);
    }

    @Bean
    RetentionRule adminSessionsRule(JdbcClient jdbc, RetentionPeriods periods) {
        return rule(
                "admin-sessions",
                "delete from identity.admin_sessions where coalesce(ended_at, least(expires_at, ends_by)) < :before",
                jdbc,
                periods);
    }
}
