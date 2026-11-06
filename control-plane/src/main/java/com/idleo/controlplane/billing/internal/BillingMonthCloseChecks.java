package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.billing.MonthCloseCheck;
import java.sql.Timestamp;
import java.time.YearMonth;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Billing's items on the month-close checklist (ADR 0055): the ledger's checks, and its payments reconciled. */
@Configuration(proxyBeanMethods = false)
class BillingMonthCloseChecks {

    /** The ledger's checks passed in a run made after the month ended, so it saw every entry dated in it. */
    @Bean
    MonthCloseCheck ledgerChecksPass(JdbcClient jdbc) {
        return new MonthCloseCheck() {
            @Override
            public int order() {
                return 10;
            }

            @Override
            public String name() {
                return "The ledger's checks pass";
            }

            @Override
            public Result check(YearMonth month) {
                var ended =
                        month.plusMonths(1).atDay(1).atStartOfDay(Periods.INDIA).toInstant();
                var found = jdbc.sql("""
                                select (select coalesce(sum(found), 0) from billing.reconciliation_checks c
                                        where c.run_id = r.id) as found,
                                       (select count(*) from billing.reconciliation_checks c where c.run_id = r.id)
                                           as checks
                                from billing.reconciliation_runs r
                                where r.finished_at >= :ended
                                order by r.finished_at desc limit 1
                                """)
                        .param("ended", Timestamp.from(ended))
                        .query((rs, n) -> new long[] {rs.getLong("found"), rs.getLong("checks")})
                        .optional();
                if (found.isEmpty() || found.get()[1] == 0) {
                    return Result.notYet("no run of the ledger's checks since the month ended; run them now");
                }
                return found.get()[0] == 0
                        ? Result.done("the latest run found nothing")
                        : Result.notYet("the latest run found " + found.get()[0] + " problems");
            }
        };
    }

    /** Nothing the payments reconciliation found about the month's days is still open (ADR 0039). */
    @Bean
    MonthCloseCheck paymentsReconciled(JdbcClient jdbc) {
        return new MonthCloseCheck() {
            @Override
            public int order() {
                return 20;
            }

            @Override
            public String name() {
                return "No open payment reconciliation items";
            }

            @Override
            public Result check(YearMonth month) {
                long open = jdbc.sql("""
                                select count(*) from billing.payment_mismatches m
                                join billing.payment_reconciliation_runs r on r.id = m.first_run_id
                                where m.state = 'open' and r.day >= :first and r.day <= :last
                                """)
                        .param("first", month.atDay(1))
                        .param("last", month.atEndOfMonth())
                        .query(Long.class)
                        .single();
                return open == 0 ? Result.done("none open") : Result.notYet(open + " open");
            }
        };
    }
}
