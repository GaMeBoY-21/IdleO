package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ControlRoom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Billing's parts of the live dashboard (ADR 0056): money, worked out by the money reports' own code over the same days
 * in India, so the two can never disagree; the renters' first top-up, for the funnel; and the settlements'
 * reconciliation, for health.
 */
@Component
class BillingControlRoom implements ControlRoom.MoneySource, ControlRoom.FunnelSource, ControlRoom.HealthSource {

    private final MoneyReports reports;
    private final JdbcClient jdbc;

    BillingControlRoom(MoneyReports reports, JdbcClient jdbc) {
        this.reports = reports;
        this.jdbc = jdbc;
    }

    @Override
    public ControlRoom.Money money(Instant now) {
        var today = LocalDate.ofInstant(now, Periods.INDIA);
        var days = reports.headlines(MoneyReports.Interval.DAY, today.minusDays(29), today);
        return new ControlRoom.Money(
                window(days, today, today),
                window(days, today.minusDays(6), today),
                window(days, today.minusDays(29), today));
    }

    private static ControlRoom.MoneyWindow window(List<MoneyReports.Row> days, LocalDate from, LocalDate to) {
        long gmv = 0;
        long take = 0;
        long refunds = 0;
        long chargebacks = 0;
        for (var day : days) {
            if (!day.start().isBefore(from) && !day.start().isAfter(to)) {
                gmv += day.figures().getOrDefault("gmv", 0L);
                take += day.figures().getOrDefault("take", 0L);
                refunds += day.figures().getOrDefault("refunds", 0L);
                chargebacks += day.figures().getOrDefault("chargebacks", 0L);
            }
        }
        return new ControlRoom.MoneyWindow(gmv, take, refunds, chargebacks);
    }

    @Override
    public List<ControlRoom.Step> steps() {
        long toppedUp = jdbc.sql("select count(distinct user_id) from billing.topups where status = 'paid'")
                .query(Long.class)
                .single();
        return List.of(new ControlRoom.Step("renters", 2, "first top-up", toppedUp));
    }

    @Override
    public List<ControlRoom.Reached> reached(Instant since) {
        return jdbc.sql("""
                        select user_id, min(paid_at) as first from billing.topups
                        where status = 'paid' and paid_at >= :from group by user_id
                        """)
                .param("from", java.sql.Timestamp.from(since))
                .query((rs, n) -> new ControlRoom.Reached(
                        "renters",
                        2,
                        "first top-up",
                        rs.getObject("user_id", java.util.UUID.class),
                        rs.getTimestamp("first").toInstant()))
                .list();
    }

    /**
     * What the latest wallet reconciliation found wrong, an unbalanced ledger included (zero before the first run); and
     * Razorpay's settlements whose items don't all agree with the ledger yet.
     */
    @Override
    public List<ControlRoom.Check> checks(Instant now) {
        long mismatches = jdbc.sql(
                        "select mismatches from billing.reconciliation_runs order by finished_at desc, id desc limit 1")
                .query(Long.class)
                .optional()
                .orElse(0L);
        var wallets = new ControlRoom.Check(
                "wallet reconciliation",
                mismatches == 0,
                mismatches == 0
                        ? "the latest run found nothing wrong"
                        : "the latest run found " + mismatches + " mismatch" + (mismatches == 1 ? "" : "es"));
        long open = jdbc.sql("select count(*) from billing.settlements where state <> 'matched'")
                .query(Long.class)
                .single();
        return List.of(
                wallets,
                new ControlRoom.Check(
                        "settlement reconciliation",
                        open == 0,
                        open == 0
                                ? "every settlement agrees with the ledger"
                                : open + " settlement" + (open == 1 ? "" : "s") + " with items to resolve"));
    }
}
