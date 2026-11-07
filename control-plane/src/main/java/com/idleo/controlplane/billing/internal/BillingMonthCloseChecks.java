package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.billing.MonthCloseCheck;
import java.sql.Timestamp;
import java.time.YearMonth;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Billing's items on the month-close checklist (ADR 0055): the ledger's checks, its payments reconciled, and Razorpay's
 * settlements reconciled.
 */
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

    /** Every item the month's settlements carried, and every payment missing from them, matches or is resolved. */
    @Bean
    MonthCloseCheck settlementsReconciled(RazorpaySettlements settlements) {
        return new MonthCloseCheck() {
            @Override
            public int order() {
                return 30;
            }

            @Override
            public String name() {
                return "Settlements reconciled";
            }

            @Override
            public Result check(YearMonth month) {
                long open = settlements.openIn(
                        month.atDay(1).atStartOfDay(Periods.INDIA).toInstant(),
                        month.plusMonths(1).atDay(1).atStartOfDay(Periods.INDIA).toInstant());
                return open == 0 ? Result.done("every item matches or is resolved") : Result.notYet(open + " open");
            }
        };
    }

    /** The month's CA pack has been made (ADR 0055). */
    @Bean
    MonthCloseCheck caPackMade(CaPacks packs) {
        return new MonthCloseCheck() {
            @Override
            public int order() {
                return 50;
            }

            @Override
            public String name() {
                return "The CA pack is generated";
            }

            @Override
            public Result check(YearMonth month) {
                return packs.made(month)
                        ? Result.done("made; the CA has its files")
                        : Result.notYet("ask for the month's CA pack on Tax");
            }
        };
    }
}
