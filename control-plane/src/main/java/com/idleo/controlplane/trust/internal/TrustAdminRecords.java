package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions of what an admin changes in trust (ADR 0051). */
@Component
class TrustAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    TrustAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of(
                "enforcement",
                AdminRecords.byId(jdbc, "id", "select version from trust.enforcements where id = :id"),
                "appeal",
                AdminRecords.byId(jdbc, "id", "select version from trust.appeals where id = :id"),
                "abuse_item",
                AdminRecords.byId(jdbc, "id", "select version from trust.abuse_items where id = :id"),
                "blocklist_addition",
                AdminRecords.byId(jdbc, "additionId", "select version from trust.blocklist_additions where id = :id"),
                "dispute",
                AdminRecords.byId(jdbc, "id", "select version from trust.disputes where id = :id"));
    }
}
