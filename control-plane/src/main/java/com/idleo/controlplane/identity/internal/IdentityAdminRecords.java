package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions of what an admin changes in identity (ADR 0051). */
@Component
class IdentityAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    IdentityAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of(
                "user",
                AdminRecords.byId(jdbc, "id", "select version from identity.users where id = :id"),
                "admin",
                AdminRecords.byId(jdbc, "id", "select version from identity.admins where user_id = :id"),
                "approval",
                AdminRecords.byId(jdbc, "id", "select version from identity.approval_requests where id = :id"));
    }
}
