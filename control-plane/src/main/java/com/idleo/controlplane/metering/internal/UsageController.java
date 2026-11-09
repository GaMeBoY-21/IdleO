package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.identity.CurrentUser;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A renter's use this month (ADR 0058, the renter app's Home): what was charged and for how many minutes, each day of
 * the month in India so far. A day's figure is its settlements less the minutes given back that day for a lost host
 * (ADR 0017's waivers), as the session screens count them; a refund for a report is the credit's, not a day's use.
 */
@RestController
@ConditionalOnWebApplication
class UsageController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final Clock clock;

    UsageController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    record UsageDay(LocalDate date, long spentPaise, long minutes) {}

    /**
     * @param month {@code 2026-11}, in India
     * @param days the first of the month to today, oldest first
     */
    record MonthUsage(String month, long spentPaise, long minutes, List<UsageDay> days) {}

    @GetMapping("/v1/usage")
    MonthUsage usage(@AuthenticationPrincipal CurrentUser user) {
        var today = Instant.now(clock).atZone(IST).toLocalDate();
        var month = YearMonth.from(today);
        var from = Timestamp.from(month.atDay(1).atStartOfDay(IST).toInstant());
        var spent = new HashMap<LocalDate, Long>();
        var minutes = new HashMap<LocalDate, Long>();
        add(spent, minutes, 1, """
                select (s.settled_at at time zone 'Asia/Kolkata')::date as day,
                       sum(s.amount_paise) as paise, sum(s.minutes) as minutes
                from metering.settlements s join metering.metered_sessions m using (session_id)
                where m.renter_id = :renter and s.settled_at >= :from
                group by 1
                """, user, from);
        add(spent, minutes, -1, """
                select (w.created_at at time zone 'Asia/Kolkata')::date as day,
                       sum(w.refunded_paise) as paise, sum(w.refunded_minutes) as minutes
                from metering.waivers w join metering.metered_sessions m using (session_id)
                where m.renter_id = :renter and w.created_at >= :from
                group by 1
                """, user, from);
        var days = new ArrayList<UsageDay>();
        for (var day = month.atDay(1); !day.isAfter(today); day = day.plusDays(1)) {
            days.add(new UsageDay(
                    day, Math.max(0, spent.getOrDefault(day, 0L)), Math.max(0, minutes.getOrDefault(day, 0L))));
        }
        return new MonthUsage(
                month.toString(),
                days.stream().mapToLong(UsageDay::spentPaise).sum(),
                days.stream().mapToLong(UsageDay::minutes).sum(),
                days);
    }

    private void add(
            Map<LocalDate, Long> spent,
            Map<LocalDate, Long> minutes,
            int sign,
            String sql,
            CurrentUser user,
            Timestamp from) {
        jdbc.sql(sql).param("renter", user.id()).param("from", from).query(rs -> {
            var day = rs.getObject("day", LocalDate.class);
            spent.merge(day, sign * rs.getLong("paise"), Long::sum);
            minutes.merge(day, sign * rs.getLong("minutes"), Long::sum);
        });
    }
}
