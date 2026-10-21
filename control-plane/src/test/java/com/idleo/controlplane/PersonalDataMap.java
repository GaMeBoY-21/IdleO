package com.idleo.controlplane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * docs/reference/personal-data-map.md, read as the tests use it (ADR 0037): each table with its columns, the personal ones,
 * how to find one person's rows ({@code Whose}), where they are in an export ({@code Export}), and what erasure does.
 */
public final class PersonalDataMap {

    public static final Path FILE = Path.of("../docs/reference/personal-data-map.md");

    private static final Pattern TABLE_ROW = Pattern.compile("^\\| `([a-z_]+\\.[a-z_]+)` \\|");

    /** What erasure does to a table's rows of a person: the first word of its "On erasure". */
    public enum Erasure {
        DELETED,
        CLEARED,
        KEPT
    }

    /**
     * One table of the map: {@code whose} a SQL condition with {@code :user}, {@code :sessions}, {@code :hosts}, {@code
     * :uploads}, {@code :installs} or {@code :address_hash}, or null; {@code exportGroup} and {@code exportKey} where
     * the rows are in an export, or null.
     */
    public record Table(
            String name,
            List<String> columns,
            Set<String> personal,
            @Nullable String whose,
            @Nullable String exportGroup,
            @Nullable String exportKey,
            Erasure erasure) {}

    /** One person's ids, for the {@code Whose} conditions. */
    public record Person(
            UUID user,
            List<UUID> sessions,
            List<UUID> hosts,
            List<UUID> uploads,
            List<UUID> installs,
            byte[] addressHash) {

        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("user", user)
                    .addValue("sessions", orNone(sessions))
                    .addValue("hosts", orNone(hosts))
                    .addValue("uploads", orNone(uploads))
                    .addValue("installs", orNone(installs))
                    .addValue("address_hash", addressHash);
        }

        private static List<UUID> orNone(List<UUID> ids) {
            // "in ()" isn't SQL; an id nothing has stands for none.
            return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
        }
    }

    private PersonalDataMap() {}

    public static List<Table> tables() {
        try {
            var tables = new ArrayList<Table>();
            for (var line : Files.readAllLines(FILE)) {
                var row = TABLE_ROW.matcher(line);
                if (!row.find()) {
                    continue;
                }
                var cells = Arrays.stream(line.strip()
                                .substring(1, line.strip().length() - 1)
                                .split("\\|"))
                        .map(String::strip)
                        .toList();
                if (cells.size() != 7) {
                    throw new IllegalStateException(row.group(1) + " has " + cells.size() + " cells, not 7");
                }
                var columns = Arrays.stream(cells.get(1).split(","))
                        .map(column -> column.replace("*", "").strip())
                        .toList();
                var personal = Arrays.stream(cells.get(1).split(","))
                        .map(String::strip)
                        .filter(column -> column.startsWith("**"))
                        .map(column -> column.replace("*", ""))
                        .collect(Collectors.toCollection(TreeSet::new));
                var whose = cells.get(2).replace("`", "").strip();
                var export = cells.get(3).strip();
                var exportGroup = export.contains(":")
                        ? export.substring(0, export.indexOf(':')).strip()
                        : null;
                var exportKey = export.contains(":")
                        ? export.substring(export.indexOf(':') + 1).strip()
                        : null;
                var first = cells.get(6).split("[^A-Za-z]")[0].toUpperCase(Locale.ROOT);
                tables.add(new Table(
                        row.group(1),
                        columns,
                        personal,
                        whose.equals("—") ? null : whose,
                        exportGroup,
                        exportKey,
                        Erasure.valueOf(first)));
            }
            return tables;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The person's rows of the table, as maps; empty for a table no condition finds a person in. */
    public static List<Map<String, Object>> rows(NamedParameterJdbcTemplate jdbc, Table table, Person person) {
        if (table.whose() == null) {
            return List.of();
        }
        return jdbc.queryForList("select * from " + table.name() + " where " + table.whose(), person.params());
    }
}
