package com.idleo.controlplane.billing.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The money reports (ADR 0055), every figure read from the ledger and defined once here as the entries it sums, so each
 * links to those entries and ties to them: the headline figures by day, week or month (India's), and the liabilities at
 * a moment.
 */
@Component
class MoneyReports {

    private final JdbcClient jdbc;
    private final LedgerExplorer explorer;

    MoneyReports(JdbcClient jdbc, LedgerExplorer explorer) {
        this.jdbc = jdbc;
        this.explorer = explorer;
    }

    /** Which side of an account a term counts: one side, or the net on its normal side. */
    enum Side {
        DEBITS,
        CREDITS,
        NET
    }

    /** Part of a figure: the entries on an account, of one cause or of all, counted on a side, added or taken away. */
    record Term(Account account, @Nullable String refType, Side side, int sign) {}

    /** A headline figure: its name in the API, and the entries that make it. */
    record Figure(String name, List<Term> terms) {}

    static final List<Figure> HEADLINES = List.of(
            new Figure(
                    "gmv",
                    List.of(
                            new Term(Account.HOST_PAYABLE, SessionChargeService.CHARGE_REF, Side.CREDITS, 1),
                            new Term(Account.PLATFORM_REVENUE, SessionChargeService.CHARGE_REF, Side.CREDITS, 1))),
            new Figure("take", List.of(new Term(Account.PLATFORM_REVENUE, null, Side.NET, 1))),
            new Figure(
                    "refunds", List.of(new Term(Account.REFUNDS_PENDING, CreditRefunds.PROCESSED_REF, Side.DEBITS, 1))),
            new Figure(
                    "chargebacks",
                    List.of(new Term(Account.GATEWAY_CLEARING, Chargebacks.SETTLE_REF, Side.CREDITS, 1))),
            new Figure("promotions", List.of(new Term(Account.PROMOTIONS, null, Side.NET, 1))),
            new Figure("gatewayFees", List.of(new Term(Account.GATEWAY_FEES, null, Side.NET, 1))),
            new Figure(
                    "net",
                    List.of(
                            new Term(Account.PLATFORM_REVENUE, null, Side.NET, 1),
                            new Term(Account.FREE_CREDIT_EXPIRED, null, Side.NET, 1),
                            new Term(Account.EARNINGS_FORFEITED, null, Side.NET, 1),
                            new Term(Account.PROMOTIONS, null, Side.NET, -1),
                            new Term(Account.CHARGEBACK_LOSSES, null, Side.NET, -1),
                            new Term(Account.GATEWAY_FEES, null, Side.NET, -1),
                            new Term(Account.WRITE_OFFS, null, Side.NET, -1))));

    enum Interval {
        DAY,
        WEEK,
        MONTH;

        String sql() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One bucket: its first day, and each headline figure in paise. */
    record Row(LocalDate start, Map<String, Long> figures) {}

    /** The headline figures by {@code interval}, from {@code from} to {@code to} (days in India, {@code to} included). */
    List<Row> headlines(Interval interval, LocalDate from, LocalDate to) {
        var start = from.atStartOfDay(Periods.INDIA).toInstant();
        var end = to.plusDays(1).atStartOfDay(Periods.INDIA).toInstant();
        record Key(LocalDate bucket, Account account, String refType) {}
        var sums = new HashMap<Key, long[]>();
        var buckets = new TreeMap<LocalDate, Boolean>();
        jdbc.sql("""
                        select date_trunc(:interval, created_at at time zone 'Asia/Kolkata')::date as bucket,
                               account, ref_type,
                               coalesce(sum(amount_paise) filter (where direction = 'debit'), 0) as debits,
                               coalesce(sum(amount_paise) filter (where direction = 'credit'), 0) as credits
                        from billing.ledger_entries
                        where created_at >= :start and created_at < :end
                        group by 1, 2, 3
                        """)
                .param("interval", interval.sql())
                .param("start", Timestamp.from(start))
                .param("end", Timestamp.from(end))
                .query(rs -> {
                    var bucket = rs.getObject("bucket", LocalDate.class);
                    buckets.put(bucket, true);
                    sums.put(
                            new Key(bucket, Account.fromDb(rs.getString("account")), rs.getString("ref_type")),
                            new long[] {rs.getLong("debits"), rs.getLong("credits")});
                });
        var rows = new ArrayList<Row>();
        for (var bucket : buckets.keySet()) {
            var figures = new java.util.LinkedHashMap<String, Long>();
            for (var figure : HEADLINES) {
                long value = 0;
                for (var term : figure.terms()) {
                    for (var sum : sums.entrySet()) {
                        var key = sum.getKey();
                        if (key.bucket().equals(bucket)
                                && key.account() == term.account()
                                && (term.refType() == null || term.refType().equals(key.refType()))) {
                            value += term.sign() * counted(term, sum.getValue()[0], sum.getValue()[1]);
                        }
                    }
                }
                figures.put(figure.name(), value);
            }
            rows.add(new Row(bucket, figures));
        }
        return rows;
    }

    private static long counted(Term term, long debits, long credits) {
        return switch (term.side()) {
            case DEBITS -> debits;
            case CREDITS -> credits;
            case NET -> term.account().raisedByCredits ? credits - debits : debits - credits;
        };
    }

    /** What hosts are owed at a moment, by how long ago they earned it, the oldest paid first. */
    record Aged(long upTo7DaysPaise, long days8To14Paise, long days15To30Paise, long over30DaysPaise) {}

    /** Free credit that expired unspent in a month (breakage), and what's still to expire in it. */
    record Breakage(YearMonth month, long expiredPaise, long toExpirePaise) {}

    record Liabilities(
            Instant at,
            long renterPurchasedPaise,
            long renterFreePaise,
            long hostPayablePaise,
            Aged hostPayableAged,
            List<Breakage> breakage) {}

    /**
     * The liabilities at {@code at}: renters' credit, bought and free; what hosts are owed, aged; and free credit's
     * breakage, by month, the twelve months to {@code at} and the three after it.
     */
    Liabilities liabilities(Instant at) {
        var balances = new HashMap<Account, Long>();
        for (var balance : explorer.balances(at, null)) {
            balances.put(balance.account(), balance.balancePaise());
        }
        // Each machine's balance sits in its newest earnings: payouts take the oldest first. So a credit is still owed
        // in the part the newer credits don't cover.
        var aged = jdbc.sql("""
                        with owed as (
                            select owner_id, sum(case direction when 'credit' then amount_paise else -amount_paise end)
                                       as balance
                            from billing.ledger_entries
                            where account = 'host_payable' and created_at <= cast(:at as timestamptz)
                            group by owner_id
                            having sum(case direction when 'credit' then amount_paise else -amount_paise end) > 0
                        ), earned as (
                            select e.owner_id, e.created_at, e.amount_paise,
                                   sum(e.amount_paise) over (partition by e.owner_id order by e.created_at desc, e.id desc)
                                       - e.amount_paise as newer
                            from billing.ledger_entries e join owed o on o.owner_id = e.owner_id
                            where e.account = 'host_payable' and e.direction = 'credit' and e.created_at <= cast(:at as timestamptz)
                        ), parts as (
                            select e.created_at, least(e.amount_paise, greatest(0, o.balance - e.newer)) as part
                            from earned e join owed o on o.owner_id = e.owner_id
                        )
                        select coalesce(sum(part) filter (where created_at > cast(:at as timestamptz) - interval '7 days'), 0) as d7,
                               coalesce(sum(part) filter (where created_at <= cast(:at as timestamptz) - interval '7 days'
                                                            and created_at > cast(:at as timestamptz) - interval '14 days'), 0) as d14,
                               coalesce(sum(part) filter (where created_at <= cast(:at as timestamptz) - interval '14 days'
                                                            and created_at > cast(:at as timestamptz) - interval '30 days'), 0) as d30,
                               coalesce(sum(part) filter (where created_at <= cast(:at as timestamptz) - interval '30 days'), 0) as older
                        from parts
                        """)
                .param("at", Timestamp.from(at))
                .query((rs, n) -> new Aged(rs.getLong("d7"), rs.getLong("d14"), rs.getLong("d30"), rs.getLong("older")))
                .single();
        var month = YearMonth.from(at.atZone(Periods.INDIA));
        var first = month.minusMonths(11);
        var last = month.plusMonths(3);
        var expired = new HashMap<YearMonth, Long>();
        jdbc.sql("""
                        select date_trunc('month', created_at at time zone 'Asia/Kolkata')::date as month,
                               sum(case direction when 'credit' then amount_paise else -amount_paise end) as paise
                        from billing.ledger_entries
                        where account = 'free_credit_expired' and created_at >= :from and created_at <= :at
                        group by 1
                        """)
                .param(
                        "from",
                        Timestamp.from(
                                first.atDay(1).atStartOfDay(Periods.INDIA).toInstant()))
                .param("at", Timestamp.from(at))
                .query(rs -> {
                    expired.put(YearMonth.from(rs.getObject("month", LocalDate.class)), rs.getLong("paise"));
                });
        var toExpire = new HashMap<YearMonth, Long>();
        jdbc.sql("""
                        select date_trunc('month', expires_at at time zone 'Asia/Kolkata')::date as month,
                               sum(remaining_paise) as paise
                        from billing.credit_lots
                        where kind = 'free' and remaining_paise > 0 and expires_at > :at and expires_at < :until
                        group by 1
                        """)
                .param("at", Timestamp.from(at))
                .param(
                        "until",
                        Timestamp.from(last.plusMonths(1)
                                .atDay(1)
                                .atStartOfDay(Periods.INDIA)
                                .toInstant()))
                .query(rs -> {
                    toExpire.put(YearMonth.from(rs.getObject("month", LocalDate.class)), rs.getLong("paise"));
                });
        var breakage = new ArrayList<Breakage>();
        for (var each = first; !each.isAfter(last); each = each.plusMonths(1)) {
            breakage.add(new Breakage(each, expired.getOrDefault(each, 0L), toExpire.getOrDefault(each, 0L)));
        }
        return new Liabilities(
                at,
                balances.getOrDefault(Account.RENTER_PURCHASED, 0L),
                balances.getOrDefault(Account.RENTER_WALLET, 0L),
                balances.getOrDefault(Account.HOST_PAYABLE, 0L),
                aged,
                breakage);
    }
}
