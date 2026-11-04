package com.idleo.controlplane.identity;

import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The versions of the records an admin can change (ADR 0051), each kept by the module that owns the record. An admin write
 * names its record with {@code @RequiresPermission(record = "…")}, and its {@code If-Match} must carry that record's
 * version as the admin read it: a record changed since then gives a 412.
 */
public interface AdminRecords {

    /** Each record's name, and how to read its version from the endpoint's path variables; empty when there's no such record. */
    Map<String, Function<Map<String, String>, OptionalLong>> versions();

    /** A version read by one UUID path variable, passed to {@code sql} as {@code :id}. */
    static Function<Map<String, String>, OptionalLong> byId(JdbcClient jdbc, String variable, String sql) {
        return variables -> {
            UUID id;
            try {
                id = UUID.fromString(variables.getOrDefault(variable, ""));
            } catch (IllegalArgumentException notAnId) {
                return OptionalLong.empty();
            }
            return jdbc.sql(sql)
                    .param("id", id)
                    .query(Long.class)
                    .optional()
                    .map(OptionalLong::of)
                    .orElseGet(OptionalLong::empty);
        };
    }
}
