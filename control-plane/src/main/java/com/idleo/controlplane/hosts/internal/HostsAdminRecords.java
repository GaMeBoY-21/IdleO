package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions of what an admin changes in hosts (ADR 0051). */
@Component
class HostsAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    HostsAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of(
                "host",
                AdminRecords.byId(jdbc, "id", "select version from hosts.hosts where id = :id"),
                "rollout",
                variables -> jdbc.sql("select version from hosts.agent_rollouts where release = :release")
                        .param("release", variables.getOrDefault("id", ""))
                        .query(Long.class)
                        .optional()
                        .map(java.util.OptionalLong::of)
                        .orElse(java.util.OptionalLong.empty()));
    }
}
