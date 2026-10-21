package com.idleo.controlplane;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rows of a module's own tables as a person's export reads them (ADR 0037, {@link PersonalData#export}): each row a
 * map from the column's name in camelCase to its value, times as ISO-8601 in UTC, ids as text, JSON columns as JSON,
 * arrays as lists, and every {@code *_paise} column with a {@code *Rupees} twin written by {@link Rupees}, so a person
 * reads money as money and a program reads it as integer paise.
 */
@Component
public class ExportRows {

    private final JsonMapper json;

    ExportRows(JsonMapper json) {
        this.json = json;
    }

    /** The rows {@code sql} finds with {@code params}. */
    public List<Map<String, Object>> list(JdbcClient jdbc, String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query((rs, n) -> row(rs)).list();
    }

    /** The one row {@code sql} finds, or null. */
    public @Nullable Map<String, Object> one(JdbcClient jdbc, String sql, Map<String, ?> params) {
        var rows = list(jdbc, sql, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        var meta = rs.getMetaData();
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            var name = meta.getColumnLabel(i);
            var type = meta.getColumnTypeName(i);
            var value = "jsonb".equals(type) || "json".equals(type)
                    ? (rs.getString(i) == null ? null : json.readTree(rs.getString(i)))
                    : value(rs.getObject(i));
            row.put(camel(name), value);
            if (name.endsWith("_paise") && value instanceof Number paise) {
                row.put(
                        camel(name.substring(0, name.length() - "_paise".length())) + "Rupees",
                        Rupees.format(paise.longValue()));
            }
        }
        return row;
    }

    private @Nullable Object value(@Nullable Object value) throws SQLException {
        return switch (value) {
            case null -> null;
            case Timestamp time -> time.toInstant().toString();
            case UUID id -> id.toString();
            case Array array -> {
                var items = new ArrayList<@Nullable Object>();
                for (var item : (Object[]) array.getArray()) {
                    items.add(value(item));
                }
                yield items;
            }
            case byte[] _ -> null;
            default -> value;
        };
    }

    static String camel(String snake) {
        var out = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
