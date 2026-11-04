package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions of what an admin changes in payouts (ADR 0051). */
@Component
class PayoutsAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    PayoutsAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of(
                "pan",
                AdminRecords.byId(jdbc, "userId", "select version from payouts.pans where user_id = :id"),
                "payout_method",
                AdminRecords.byId(jdbc, "id", "select version from payouts.methods where id = :id"),
                "payout_mismatch",
                AdminRecords.byId(jdbc, "id", "select version from payouts.mismatches where id = :id"));
    }
}
