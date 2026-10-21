package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.RetentionPeriods;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The nightly wallet check's results are kept a year (ADR 0037); its mismatches name a person by id. The latest run
 * always stays, for the admin console.
 */
@Configuration(proxyBeanMethods = false)
class ReconciliationRetention {

    @Bean
    RetentionRule reconciliationRunsRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of("reconciliation-runs", now -> {
            var before = Timestamp.from(now.minus(periods.of("reconciliation-runs")));
            var old = "select id from billing.reconciliation_runs where finished_at < :before"
                    + " and id <> (select id from billing.reconciliation_runs order by finished_at desc limit 1)";
            long mismatches = jdbc.sql("delete from billing.reconciliation_mismatches where run_id in (" + old + ")")
                    .param("before", before)
                    .update();
            return mismatches
                    + jdbc.sql("delete from billing.reconciliation_runs where id in (" + old + ")")
                            .param("before", before)
                            .update();
        });
    }
}
