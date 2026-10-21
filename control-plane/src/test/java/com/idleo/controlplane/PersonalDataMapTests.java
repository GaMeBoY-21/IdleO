package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The personal data map (docs/reference/personal-data-map.md, ADR 0037) lists every table with every column, and every
 * storage key prefix the control plane builds. A new table, column or prefix fails here until the map says what it
 * holds, why, how long and what erasure does with it.
 */
@ControlPlaneTest
class PersonalDataMapTests {

    static final Path MAP = Path.of("../docs/reference/personal-data-map.md");

    /** A map row: | `schema.table` | columns | … */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\| `([a-z_]+\\.[a-z_]+)` \\| ([^|]+) \\|");

    /** A storage row: | `prefix/` … */
    private static final Pattern PREFIX_ROW = Pattern.compile("^\\| `([a-z0-9_-]+/)`");

    /** A string literal that is a bare key segment, such as {@code "uploads/"}. */
    private static final Pattern PREFIX_LITERAL = Pattern.compile("\"([a-z][a-z0-9_-]*/)\"");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void listsEveryTableWithEveryColumn() throws IOException {
        var mapped = mappedTables();
        var actual = new TreeMap<String, List<String>>();
        jdbc.query("""
                select c.table_schema || '.' || c.table_name as name, c.column_name
                from information_schema.columns c
                join pg_class k on k.relname = c.table_name
                join pg_namespace n on n.oid = k.relnamespace and n.nspname = c.table_schema
                where c.table_schema not in ('pg_catalog', 'information_schema', 'public')
                  and k.relkind in ('r', 'p') and not k.relispartition
                order by c.table_schema, c.table_name, c.ordinal_position
                """, rs -> {
            actual.computeIfAbsent(rs.getString("name"), name -> new ArrayList<>())
                    .add(rs.getString("column_name"));
        });

        assertThat(mapped.keySet())
                .as("tables in the database and in docs/reference/personal-data-map.md")
                .containsExactlyInAnyOrderElementsOf(actual.keySet());
        actual.forEach((table, columns) -> assertThat(mapped.get(table))
                .as("the columns of %s in the map", table)
                .containsExactlyInAnyOrderElementsOf(columns));
    }

    @Test
    void listsEveryStoragePrefixTheControlPlaneBuilds() throws IOException {
        var mapped = new TreeSet<String>();
        for (var line : Files.readAllLines(MAP)) {
            var row = PREFIX_ROW.matcher(line);
            if (row.find()) {
                mapped.add(row.group(1));
            }
        }
        var literals = new TreeSet<String>();
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (var source :
                    sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                var found = PREFIX_LITERAL.matcher(Files.readString(source));
                while (found.find()) {
                    literals.add(found.group(1));
                }
            }
        }

        assertThat(mapped).as("prefixes in docs/reference/personal-data-map.md").containsAll(literals);
    }

    /** The map's tables and their columns, as written, with the bold marks taken off. */
    static Map<String, List<String>> mappedTables() throws IOException {
        var tables = new LinkedHashMap<String, List<String>>();
        for (var line : Files.readAllLines(MAP)) {
            var row = TABLE_ROW.matcher(line);
            if (row.find()) {
                var columns = Pattern.compile(",")
                        .splitAsStream(row.group(2))
                        .map(column -> column.replace("*", "").strip())
                        .toList();
                assertThat(tables.put(row.group(1), columns))
                        .as("%s listed once", row.group(1))
                        .isNull();
            }
        }
        return tables;
    }

    /** The map's personal columns (bold), by table. */
    static Map<String, Set<String>> personalColumns() throws IOException {
        var personal = new TreeMap<String, Set<String>>();
        for (var line : Files.readAllLines(MAP)) {
            var row = TABLE_ROW.matcher(line);
            if (row.find()) {
                personal.put(
                        row.group(1),
                        Pattern.compile(",")
                                .splitAsStream(row.group(2))
                                .map(String::strip)
                                .filter(column -> column.startsWith("**"))
                                .map(column -> column.replace("*", ""))
                                .collect(Collectors.toCollection(TreeSet::new)));
            }
        }
        return personal;
    }
}
