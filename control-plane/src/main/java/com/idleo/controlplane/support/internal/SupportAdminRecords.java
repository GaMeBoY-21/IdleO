package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions of what an admin changes in support (ADR 0051). */
@Component
class SupportAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    SupportAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of("ticket", AdminRecords.byId(jdbc, "id", "select version from support.tickets where id = :id"));
    }
}
