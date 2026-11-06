package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.RequiresPermission;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The money reports for the console (ADR 0055): the headline figures by day, week or month, and the liabilities at a
 * moment. Each figure comes with the entries it sums, so the console links every number to the ledger behind it.
 */
@RestController
@RequestMapping("/admin/v1/money")
@ConditionalOnWebApplication
class AdminMoneyReportsController {

    /** At most a little over a year of days, so a report stays one page. */
    private static final long MOST_DAYS = 400;

    private final MoneyReports reports;
    private final Clock clock;

    AdminMoneyReportsController(MoneyReports reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    /** Part of a figure: the entries on an account, of one cause or all, counted on a side, added (1) or taken away (-1). */
    record AdminFigureTerm(
            String account,
            @Nullable String refType,

            @Schema(allowableValues = {"debits", "credits", "net"})
            String side,

            int sign) {}

    record AdminFigure(String name, List<AdminFigureTerm> terms) {}

    /** One bucket's first day (India's) and each figure in paise. */
    record AdminMoneyRow(LocalDate start, Map<String, Long> figures) {}

    record AdminMoneyReport(
            String interval, LocalDate from, LocalDate to, List<AdminFigure> figures, List<AdminMoneyRow> rows) {}

    record AdminAgedPayables(long upTo7DaysPaise, long days8To14Paise, long days15To30Paise, long over30DaysPaise) {}

    record AdminBreakage(String month, long expiredPaise, long toExpirePaise) {}

    record AdminLiabilities(
            Instant at,
            long renterPurchasedPaise,
            long renterFreePaise,
            long hostPayablePaise,
            AdminAgedPayables hostPayableAged,
            List<AdminBreakage> breakage) {}

    /**
     * GMV, the take, refunds, chargebacks, what promotions cost, Razorpay's fees and the net, by {@code interval} ({@code
     * day}, {@code week} or {@code month}), from {@code from} to {@code to} (dates in India, both included; the last 30
     * days when not given).
     */
    @RequiresPermission(AdminPermission.MONEY_READ)
    @GetMapping("/report")
    AdminMoneyReport report(
            @RequestParam(required = false) @Nullable @Schema(allowableValues = {"day", "week", "month"})
                    String interval,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to) {
        MoneyReports.Interval asked;
        try {
            asked = interval == null
                    ? MoneyReports.Interval.DAY
                    : MoneyReports.Interval.valueOf(interval.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.invalid("interval", "is day, week or month.");
        }
        var today = LocalDate.ofInstant(clock.instant(), Periods.INDIA);
        var end = date("to", to, today);
        var start = date("from", from, end.minusDays(29));
        if (start.isAfter(end)) {
            throw ApiProblem.invalid("from", "is on or before to.");
        }
        if (ChronoUnit.DAYS.between(start, end) > MOST_DAYS) {
            throw ApiProblem.invalid("from", "is at most " + MOST_DAYS + " days before to.");
        }
        return new AdminMoneyReport(
                asked.sql(),
                start,
                end,
                MoneyReports.HEADLINES.stream()
                        .map(figure -> new AdminFigure(
                                figure.name(),
                                figure.terms().stream()
                                        .map(term -> new AdminFigureTerm(
                                                term.account().dbValue,
                                                term.refType(),
                                                term.side().name().toLowerCase(Locale.ROOT),
                                                term.sign()))
                                        .toList()))
                        .toList(),
                reports.headlines(asked, start, end).stream()
                        .map(row -> new AdminMoneyRow(row.start(), row.figures()))
                        .toList());
    }

    /** Renters' credit, what hosts are owed and how long, and free credit's breakage, at {@code at} (now by default). */
    @RequiresPermission(AdminPermission.MONEY_READ)
    @GetMapping("/liabilities")
    AdminLiabilities liabilities(@RequestParam(required = false) @Nullable String at) {
        Instant moment;
        try {
            moment = at == null || at.isBlank() ? clock.instant() : Instant.parse(at.strip());
        } catch (DateTimeParseException e) {
            throw ApiProblem.invalid("at", "is a moment such as 2026-10-31T18:30:00Z.");
        }
        var found = reports.liabilities(moment);
        var aged = found.hostPayableAged();
        return new AdminLiabilities(
                found.at(),
                found.renterPurchasedPaise(),
                found.renterFreePaise(),
                found.hostPayablePaise(),
                new AdminAgedPayables(
                        aged.upTo7DaysPaise(), aged.days8To14Paise(), aged.days15To30Paise(), aged.over30DaysPaise()),
                found.breakage().stream()
                        .map(each ->
                                new AdminBreakage(each.month().toString(), each.expiredPaise(), each.toExpirePaise()))
                        .toList());
    }

    private static LocalDate date(String name, @Nullable String value, LocalDate otherwise) {
        if (value == null || value.isBlank()) {
            return otherwise;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw ApiProblem.invalid(name, "is a date such as 2026-10-31.");
        }
    }
}
