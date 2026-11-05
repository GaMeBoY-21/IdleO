package com.idleo.controlplane.queues.internal;

import com.idleo.controlplane.identity.AdminRecords;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The versions {@code If-Match} is checked against (ADR 0051). */
@Component
class QueuesAdminRecords implements AdminRecords {

    private final JdbcClient jdbc;

    QueuesAdminRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Function<Map<String, String>, OptionalLong>> versions() {
        return Map.of(
                "queue_item",
                AdminRecords.byId(jdbc, "id", "select version from queues.items where id = :id"),
                "macro",
                AdminRecords.byId(jdbc, "id", "select version from queues.macros where id = :id"),
                "sla_target",
                variables -> jdbc.sql("select version from queues.sla_targets where sla = :sla")
                        .param("sla", variables.getOrDefault("sla", ""))
                        .query(Long.class)
                        .optional()
                        .map(OptionalLong::of)
                        .orElse(OptionalLong.empty()));
    }
}
