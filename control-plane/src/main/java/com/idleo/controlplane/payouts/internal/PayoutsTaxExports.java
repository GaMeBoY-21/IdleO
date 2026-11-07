package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.Csv;
import com.idleo.controlplane.FinancialYear;
import com.idleo.controlplane.billing.TaxExport;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Payouts' files for the CA (ADR 0040, ADR 0055): tax deducted at source under 194-O (206AA without a PAN), each payout
 * that didn't come back counted in the month or quarter it was made. The quarter's deductions payout by payout, 26Q's
 * data and Form 16A's data per host, with each host's whole PAN and name as on it; and for the month's CA pack, the
 * month's TDS and its payouts. The rates come from {@code legal.tds}; CA(confirm) both, and each format.
 */
@Configuration(proxyBeanMethods = false)
class PayoutsTaxExports {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    /** The quarter of the financial year that holds {@code month}: its first month. */
    static YearMonth quarterStart(YearMonth month) {
        int offset = (month.getMonthValue() + 8) % 12;
        return month.minusMonths(offset % 3);
    }

    /** {@code 2026-27-Q3} for October to December 2026. */
    static String quarterName(YearMonth month) {
        var start = quarterStart(month);
        int year = start.getMonthValue() >= 4 ? start.getYear() : start.getYear() - 1;
        int quarter = start.getMonthValue() >= 4 ? (start.getMonthValue() - 4) / 3 + 1 : 4;
        return "%d-%02d-Q%d".formatted(year, (year + 1) % 100, quarter);
    }

    private static Timestamp[] range(YearMonth first, int months) {
        return new Timestamp[] {
            Timestamp.from(first.atDay(1).atStartOfDay(FinancialYear.INDIA).toInstant()),
            Timestamp.from(first.plusMonths(months)
                    .atDay(1)
                    .atStartOfDay(FinancialYear.INDIA)
                    .toInstant())
        };
    }

    private static final String PAYOUTS = """
            from payouts.payouts where created_at >= :from and created_at < :to and status not in (:returned)
            """;

    private static String day(java.sql.Timestamp at) {
        return DAY.format(ZonedDateTime.ofInstant(at.toInstant(), FinancialYear.INDIA));
    }

    private static void write(OutputStream out, String text) {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the payouts made in a period deducted: their gross, TDS and net, and how many. */
    private static Map<String, Long> sums(JdbcClient jdbc, Timestamp[] range) {
        return jdbc.sql("select count(*) as payouts, coalesce(sum(gross_paise), 0) as gross,"
                        + " coalesce(sum(tds_paise), 0) as tds, coalesce(sum(net_paise), 0) as net " + PAYOUTS)
                .param("from", range[0])
                .param("to", range[1])
                .param("returned", TaxDeduction.RETURNED)
                .query((rs, n) -> {
                    var totals = new LinkedHashMap<String, Long>();
                    totals.put("payouts", rs.getLong("payouts"));
                    totals.put("gross", rs.getLong("gross"));
                    totals.put("tds", rs.getLong("tds"));
                    totals.put("net", rs.getLong("net"));
                    return (Map<String, Long>) totals;
                })
                .single();
    }

    /** One file of payouts: its name and description, quarterly or monthly, and whether the month's pack holds it. */
    private abstract static class PayoutsFile implements TaxExport {

        final String name;
        final String description;
        final boolean quarterly;
        final JdbcClient jdbc;

        PayoutsFile(JdbcClient jdbc, String name, String description, boolean quarterly) {
            this.jdbc = jdbc;
            this.name = name;
            this.description = description;
            this.quarterly = quarterly;
        }

        Timestamp[] range(YearMonth month) {
            return quarterly ? PayoutsTaxExports.range(quarterStart(month), 3) : PayoutsTaxExports.range(month, 1);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public String fileName(YearMonth month) {
            return name + "-" + (quarterly ? quarterName(month) : month.toString()) + ".csv";
        }

        @Override
        public String contentType() {
            return "text/csv";
        }

        @Override
        public boolean inPack() {
            return !quarterly;
        }

        @Override
        public Map<String, Long> totals(YearMonth month) {
            return sums(jdbc, range(month));
        }
    }

    /** Each payout's deduction: its host's PAN and name as on it, the section and rate, the gross, TDS and net, and dates. */
    private static PayoutsFile deductions(
            JdbcClient jdbc, Pans pans, String name, String description, boolean quarterly) {
        return new PayoutsFile(jdbc, name, description, quarterly) {
            @Override
            public void write(YearMonth month, OutputStream out) {
                var range = range(month);
                PayoutsTaxExports.write(
                        out,
                        Csv.line(
                                "payout",
                                "pan",
                                "name_on_pan",
                                "holder",
                                "section",
                                "rate_percent",
                                "gross_paise",
                                "tds_paise",
                                "net_paise",
                                "deducted_on",
                                "paid_on",
                                "status"));
                jdbc.sql("select * " + PAYOUTS + " order by created_at, id")
                        .param("from", range[0])
                        .param("to", range[1])
                        .param("returned", TaxDeduction.RETURNED)
                        .withFetchSize(500)
                        .query((RowCallbackHandler) rs -> {
                            var user = rs.getObject("user_id", UUID.class);
                            var pan = pans.find(user).orElse(null);
                            var processed = rs.getTimestamp("processed_at");
                            PayoutsTaxExports.write(
                                    out,
                                    Csv.line(
                                            rs.getString("id"),
                                            pans.full(user).orElse(""),
                                            pan == null ? "" : pan.name(),
                                            pan == null ? "" : pan.holder(),
                                            rs.getString("tds_section"),
                                            BigDecimal.valueOf(rs.getInt("tds_basis_points"), 2),
                                            rs.getLong("gross_paise"),
                                            rs.getLong("tds_paise"),
                                            rs.getLong("net_paise"),
                                            day(rs.getTimestamp("created_at")),
                                            processed == null ? "" : day(processed),
                                            rs.getString("status")));
                        });
            }
        };
    }

    /** The quarter's deductions, payout by payout: the file the CA files from (ADR 0040). */
    @Bean
    TaxExport tdsDeductions(JdbcClient jdbc, Pans pans) {
        return deductions(
                jdbc, pans, "tds-deductions", "The quarter's TDS, payout by payout, with each whole PAN", true);
    }

    /** The month's deductions, payout by payout, for the month's CA pack. */
    @Bean
    TaxExport tdsMonth(JdbcClient jdbc, Pans pans) {
        return deductions(jdbc, pans, "tds", "The month's TDS, payout by payout, with each whole PAN", false);
    }

    /**
     * 26Q's deductee records for the quarter (CA(confirm) on the format): one a payout, the deductee's PAN and name, the
     * section, the date paid or credited, the amount and the tax, and the rate; a host without a PAN under 206AA.
     */
    @Bean
    TaxExport tds26q(JdbcClient jdbc, Pans pans) {
        return new PayoutsFile(
                jdbc, "tds-26q", "26Q's deductee records for the quarter (CA(confirm) on the format)", true) {
            @Override
            public void write(YearMonth month, OutputStream out) {
                var range = range(month);
                PayoutsTaxExports.write(
                        out,
                        Csv.line(
                                "deductee_pan",
                                "deductee_name",
                                "section",
                                "date_paid_or_credited",
                                "amount_paid_paise",
                                "tds_paise",
                                "rate_percent",
                                "date_deducted",
                                "no_pan"));
                jdbc.sql("select * " + PAYOUTS + " order by created_at, id")
                        .param("from", range[0])
                        .param("to", range[1])
                        .param("returned", TaxDeduction.RETURNED)
                        .withFetchSize(500)
                        .query((RowCallbackHandler) rs -> {
                            var user = rs.getObject("user_id", UUID.class);
                            var full = pans.full(user).orElse("");
                            var pan = pans.find(user).orElse(null);
                            PayoutsTaxExports.write(
                                    out,
                                    Csv.line(
                                            full.isEmpty() ? "PANNOTAVBL" : full,
                                            pan == null ? "" : pan.name(),
                                            rs.getString("tds_section"),
                                            day(rs.getTimestamp("created_at")),
                                            rs.getLong("gross_paise"),
                                            rs.getLong("tds_paise"),
                                            BigDecimal.valueOf(rs.getInt("tds_basis_points"), 2),
                                            day(rs.getTimestamp("created_at")),
                                            full.isEmpty() ? "yes" : "no"));
                        });
            }
        };
    }

    /**
     * Form 16A's data for the quarter (CA(confirm) on the format): one row a host and section, their PAN and name, the
     * amount paid, the tax deducted, and how many payouts.
     */
    @Bean
    TaxExport tds16a(JdbcClient jdbc, Pans pans) {
        return new PayoutsFile(
                jdbc, "tds-16a", "Form 16A's data per host for the quarter (CA(confirm) on the format)", true) {
            @Override
            public void write(YearMonth month, OutputStream out) {
                var range = range(month);
                PayoutsTaxExports.write(
                        out,
                        Csv.line(
                                "quarter",
                                "deductee_pan",
                                "deductee_name",
                                "section",
                                "amount_paid_paise",
                                "tds_paise",
                                "payouts"));
                var quarter = quarterName(month);
                jdbc.sql(
                                "select user_id, tds_section, sum(gross_paise) as gross, sum(tds_paise) as tds, count(*) as payouts "
                                        + PAYOUTS + " group by user_id, tds_section order by user_id, tds_section")
                        .param("from", range[0])
                        .param("to", range[1])
                        .param("returned", TaxDeduction.RETURNED)
                        .query((RowCallbackHandler) rs -> {
                            var user = rs.getObject("user_id", UUID.class);
                            var pan = pans.find(user).orElse(null);
                            PayoutsTaxExports.write(
                                    out,
                                    Csv.line(
                                            quarter,
                                            pans.full(user).orElse(""),
                                            pan == null ? "" : pan.name(),
                                            rs.getString("tds_section"),
                                            rs.getLong("gross"),
                                            rs.getLong("tds"),
                                            rs.getLong("payouts")));
                        });
            }
        };
    }

    /** The month's payouts, for the CA pack: each one's figures, status and UTR, by host id; no PAN. */
    @Bean
    TaxExport payoutsMonth(JdbcClient jdbc) {
        return new PayoutsFile(jdbc, "payouts", "The month's payouts, each with its status and UTR", false) {
            @Override
            public void write(YearMonth month, OutputStream out) {
                var range = range(month);
                PayoutsTaxExports.write(
                        out,
                        Csv.line(
                                "payout",
                                "host",
                                "batch",
                                "kind",
                                "gross_paise",
                                "tds_paise",
                                "fee_paise",
                                "net_paise",
                                "status",
                                "utr",
                                "made_on",
                                "paid_on"));
                jdbc.sql("select * " + PAYOUTS + " order by created_at, id")
                        .param("from", range[0])
                        .param("to", range[1])
                        .param("returned", TaxDeduction.RETURNED)
                        .query((RowCallbackHandler) rs -> {
                            var processed = rs.getTimestamp("processed_at");
                            PayoutsTaxExports.write(
                                    out,
                                    Csv.line(
                                            rs.getString("id"),
                                            rs.getString("user_id"),
                                            rs.getString("run_id"),
                                            rs.getString("kind"),
                                            rs.getLong("gross_paise"),
                                            rs.getLong("tds_paise"),
                                            rs.getLong("fee_paise"),
                                            rs.getLong("net_paise"),
                                            rs.getString("status"),
                                            rs.getString("utr"),
                                            day(rs.getTimestamp("created_at")),
                                            processed == null ? "" : day(processed)));
                        });
            }
        };
    }
}
