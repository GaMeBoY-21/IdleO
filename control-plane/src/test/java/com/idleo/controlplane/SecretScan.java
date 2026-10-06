package com.idleo.controlplane;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Looks for secrets in every table of the database: in each row's text form, and hex-encoded, as they
 * would appear inside a {@code bytea} column such as a queued command's protobuf payload.
 */
public final class SecretScan {

    private SecretScan() {}

    /** The tables, as {@code schema.table}, any of whose rows contain any of {@code secrets}. */
    public static List<String> tablesContaining(JdbcTemplate jdbc, String... secrets) {
        var tables = jdbc.queryForList("""
                select format('%I.%I', table_schema, table_name) from information_schema.tables
                where table_type = 'BASE TABLE' and table_schema not in ('pg_catalog', 'information_schema')
                """, String.class);
        var found = new ArrayList<String>();
        for (var table : tables) {
            for (var secret : secrets) {
                var hex = HexFormat.of().formatHex(secret.getBytes(StandardCharsets.UTF_8));
                var rows = jdbc.queryForObject(
                        "select count(*) from " + table + " t where position(? in t::text) > 0"
                                + " or position(? in t::text) > 0",
                        Integer.class,
                        secret,
                        hex);
                if (rows != null && rows > 0) {
                    found.add(table);
                }
            }
        }
        return found;
    }
}
