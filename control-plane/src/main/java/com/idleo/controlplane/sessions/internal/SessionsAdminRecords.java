package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions of what an admin changes in sessions (ADR 0051). */
@Component
class SessionsAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    SessionsAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of("session", AdminRecords.byId(jdbc, "id", "select version from sessions.sessions where id = :id"));
    }
}
