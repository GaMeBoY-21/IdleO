package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions of what an admin changes in billing (ADR 0051). A grant and an invoice never change: what changes them is a reversal or a credit note, so those are counted. */
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
                        "select 1 + (select count(*) from billing.invoices n where n.credit_note_for = i.id) from billing.invoices i where i.id = :id"));
    }
}
