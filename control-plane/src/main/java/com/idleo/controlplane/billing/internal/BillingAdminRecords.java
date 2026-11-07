package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The versions of what an admin changes in billing (ADR 0051). A grant, an invoice and an adjustment never change: what
 * changes them is a reversal or a credit note, so those are counted. A month's is its closes and reopenings.
 */
@Component
class BillingAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    BillingAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of(
                "credit_refund",
                AdminRecords.byId(jdbc, "id", "select version from billing.credit_refunds where id = :id"),
                "payment_mismatch",
                AdminRecords.byId(jdbc, "id", "select version from billing.payment_mismatches where id = :id"),
                "clawback",
                AdminRecords.byId(jdbc, "id", "select version from billing.clawbacks where id = :id"),
                "credit_grant",
                AdminRecords.byId(
                        jdbc,
                        "id",
                        "select 1 + (select count(*) from billing.credit_grant_reversals r where r.grant_id = g.id) from billing.credit_grants g where g.id = :id"),
                "invoice",
                AdminRecords.byId(
                        jdbc,
                        "id",
                        "select 1 + (select count(*) from billing.invoices n where n.credit_note_for = i.id) from billing.invoices i where i.id = :id"),
                "adjustment",
                AdminRecords.byId(
                        jdbc,
                        "id",
                        "select 1 + (select count(*) from billing.adjustments r where r.reverses_id = a.id) from billing.adjustments a where a.id = :id"),
                "period",
                this::periodVersion,
                "settlement_item",
                AdminRecords.byId(jdbc, "id", "select version from billing.settlement_items where id = :id"));
    }

    /** A month's version: 0 until it's first closed, then one more for each close and reopening (ADR 0055). */
    private OptionalLong periodVersion(Map<String, String> variables) {
        YearMonth month;
        try {
            // The month's path variable, or the approval's target id.
            month = YearMonth.parse(variables.getOrDefault("month", variables.getOrDefault("id", "")));
        } catch (DateTimeParseException notAMonth) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(jdbc.sql("select version from billing.periods where month = :month")
                .param("month", month.atDay(1))
                .query(Long.class)
                .optional()
                .orElse(0L));
    }
}
