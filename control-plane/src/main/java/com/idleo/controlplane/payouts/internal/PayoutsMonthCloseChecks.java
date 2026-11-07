package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.billing.MonthCloseCheck;
import java.time.YearMonth;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Payouts' item on the month-close checklist (ADR 0055): the month's batches released, and every payout in them settled. */
@Configuration(proxyBeanMethods = false)
class PayoutsMonthCloseChecks {

    @Bean
    MonthCloseCheck payoutBatchesSettled(JdbcClient jdbc) {
        return new MonthCloseCheck() {
            @Override
            public int order() {
                return 40;
            }

            @Override
            public String name() {
                return "The payout batches are settled";
            }

            @Override
            public Result check(YearMonth month) {
                var found = jdbc.sql("""
                                select count(*) filter (where r.state <> 'released') as unreleased,
                                       (select count(*) from payouts.payouts p
                                        where p.run_id in (select id from payouts.runs
                                                           where payday >= :first and payday <= :last)
                                          and p.status in (:open)) as on_their_way
                                from payouts.runs r
                                where r.payday >= :first and r.payday <= :last
                                """)
                        .param("first", month.atDay(1))
                        .param("last", month.atEndOfMonth())
                        .param("open", PayoutService.OPEN)
                        .query((rs, n) -> new long[] {rs.getLong("unreleased"), rs.getLong("on_their_way")})
                        .single();
                if (found[0] > 0) {
                    return Result.notYet(found[0] + " batch not released");
                }
                if (found[1] > 0) {
                    return Result.notYet(found[1] + " payouts still on their way");
                }
                return Result.done("every batch released and settled");
            }
        };
    }
}
