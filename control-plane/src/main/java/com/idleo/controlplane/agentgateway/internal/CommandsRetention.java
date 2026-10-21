package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.RetentionPeriods;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Messages to agents are kept a while after they were delivered or given up on, and then deleted (ADR 0037): a
 * StartSession holds the renter's parameters and input links. One waiting to be sent is never deleted.
 */
@Configuration(proxyBeanMethods = false)
class CommandsRetention {

    @Bean
    RetentionRule agentCommandsRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                "agent-commands",
                now -> jdbc.sql("""
                                delete from agentgateway.agent_commands
                                where status in ('acked', 'expired', 'withdrawn') and created_at < :before
                                """)
                        .param("before", Timestamp.from(now.minus(periods.of("agent-commands"))))
                        .update());
    }
}
