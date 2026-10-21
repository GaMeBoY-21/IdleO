package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
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

    static final Path MAP = PersonalDataMap.FILE;

    /** A storage row: | `prefix/` … */
    private static final Pattern PREFIX_ROW = Pattern.compile("^\\| `([a-z0-9_-]+/)`");

    /** A string literal that is a bare key segment, such as {@code "uploads/"}. */
    private static final Pattern PREFIX_LITERAL = Pattern.compile("\"([a-z][a-z0-9_-]*/)\"");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void listsEveryTableWithEveryColumn() {
        var mapped = new java.util.HashMap<String, List<String>>();
        for (var table : PersonalDataMap.tables()) {
            assertThat(mapped.put(table.name(), table.columns()))
                    .as("%s listed once", table.name())
                    .isNull();
        }
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

    @Test
    void saysForEveryTableWhatErasureDoesAndFindsAPersonWhereItCan() {
        for (var table : PersonalDataMap.tables()) {
            if (table.erasure() != PersonalDataMap.Erasure.KEPT) {
                assertThat(table.whose())
                        .as("%s is deleted or cleared, so the map says how to find a person's rows", table.name())
                        .isNotNull();
            }
            if (table.exportGroup() != null) {
                assertThat(PersonalData.GROUPS).as(table.name()).contains(table.exportGroup());
            }
        }
    }

    @Autowired
    RetentionJobs retention;

    @Autowired
    MutableClock clock;

    /** Every rule the map names has its job, every job is in the map, and each runs twice over without error. */
    @Test
    void everyRetentionRuleInTheMapHasItsJobAndEachIsIdempotent() throws IOException {
        var named = new TreeSet<String>();
        var rule = Pattern.compile("`rule: ([a-z-]+)`|^\\| `([a-z-]+)` \\| ");
        var text = Files.readString(MAP);
        var inTable = text.substring(text.indexOf("## Retention rules"));
        var mentioned = Pattern.compile("`rule: ([a-z-]+)`").matcher(text);
        while (mentioned.find()) {
            named.add(mentioned.group(1));
        }
        var rows = Pattern.compile("(?m)^\\| `([a-z-]+)` \\| ").matcher(inTable);
        var listed = new TreeSet<String>();
        while (rows.find()) {
            listed.add(rows.group(1));
        }
        assertThat(listed).as("the map's retention rules table").containsAll(named);
        assertThat(retention.rules().keySet()).as("the rules' jobs").containsExactlyInAnyOrderElementsOf(listed);
        for (var job : retention.rules().values()) {
            var now = clock.instant();
            job.apply(now);
            assertThat(job.apply(now)).as("%s, run again at once", job.name()).isZero();
        }
        assertThat(rule).isNotNull();
    }
}
