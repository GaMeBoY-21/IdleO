package com.idleo.controlplane;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One list of the admin console (ADR 0052), declared once by the module that owns it: its rows, its filters, what it can
 * be sorted by, and what goes in its CSV. The list's endpoint and its export are the same query, so a file is exactly
 * what the screen showed. Paging is by keyset: the sort's value and the row's id, never an offset, so a page is as fast
 * deep down as at the top, and a row added meanwhile moves nothing.
 *
 * <p>Everything named in SQL here is a constant of the declaration. What a request gives only picks among them by name,
 * and its values go as parameters.
 */
public final class AdminListing {

    /** How a sort's value is compared, as SQL casts it. */
    public enum Type {
        UUID("uuid"),
        TIME("timestamptz"),
        TEXT("text"),
        NUMBER("bigint");

        private final String cast;

        Type(String cast) {
            this.cast = cast;
        }
    }

    private record Sort(String sql, Type type) {}

    /** A filter: its SQL with {@code :name} for its value. */
    private record Filter(String sql) {}

    private record CsvColumn(String name, String sql) {}

    /** One page: its rows and where the next starts, or null at the end. */
    public record Page<T>(List<T> rows, @Nullable String nextCursor) {}

    /**
     * What a request asks of the list.
     *
     * @param filters each filter's value by its name; a null or blank one isn't applied
     * @param sort {@code name} or {@code name:asc} or {@code name:desc}; null for the list's own order
     */
    public record Asked(
            Map<String, @Nullable Object> filters, @Nullable String sort) {

        public static Asked of(@Nullable String sort, @Nullable Object... namesAndValues) {
            var filters = new LinkedHashMap<String, @Nullable Object>();
            for (int i = 0; i < namesAndValues.length; i += 2) {
                filters.put((String) namesAndValues[i], namesAndValues[i + 1]);
            }
            return new Asked(filters, sort);
        }
    }

    private static final String SORT_VALUE = "console_sort_value";
    private static final String ROW_ID = "console_row_id";

    private final String name;
    private final AdminPermission permission;
    private final boolean personalData;
    private final String select;
    private final String from;
    private final String id;
    private final Type idType;
    private final String fixed;
    private final String defaultSort;
    private final boolean defaultDescending;
    private final Map<String, Sort> sorts;
    private final Map<String, Filter> filters;
    private final List<CsvColumn> csv;

    private AdminListing(Builder builder) {
        this.name = builder.name;
        this.permission = builder.permission;
        this.personalData = builder.personalData;
        this.select = builder.select;
        this.from = builder.from;
        this.id = builder.id;
        this.idType = builder.idType;
        this.fixed = builder.fixed;
        this.defaultSort = builder.defaultSort;
        this.defaultDescending = builder.defaultDescending;
        this.sorts = Map.copyOf(builder.sorts);
        this.filters = Map.copyOf(builder.filters);
        this.csv = List.copyOf(builder.csv);
    }

    /** The name a list's saved views and exports go by. */
    public String name() {
        return name;
    }

    /** What an admin must hold to read it. */
    public AdminPermission permission() {
        return permission;
    }

    /** Whether its CSV holds personal data, even masked, so another admin approves the export (ADR 0051). */
    public boolean personalData() {
        return personalData;
    }

    /** Its filters' names: what an export may give. */
    public java.util.Set<String> filterNames() {
        return filters.keySet();
    }

    /** The CSV's header. */
    public List<String> header() {
        return csv.stream().map(CsvColumn::name).toList();
    }

    private record Order(String key, Sort sort, boolean descending) {}

    private Order order(@Nullable String asked) {
        if (asked == null || asked.isBlank()) {
            return new Order(defaultSort, sorts.get(defaultSort), defaultDescending);
        }
        var parts = asked.strip().split(":", 2);
        var sort = sorts.get(parts[0]);
        if (sort == null) {
            throw ApiProblem.invalid(
                    "sort", "can't sort by that. It can be: " + String.join(", ", sorts.keySet()) + ".");
        }
        if (parts.length == 2 && !parts[1].equals("asc") && !parts[1].equals("desc")) {
            throw ApiProblem.invalid("sort", "must be name:asc or name:desc.");
        }
        return new Order(parts[0], sort, parts.length == 2 ? parts[1].equals("desc") : true);
    }

    /** The filters that apply, as SQL, and their values. */
    private String where(Asked asked, Map<String, Object> params) {
        var conditions = new ArrayList<String>();
        if (!fixed.isEmpty()) {
            conditions.add(fixed);
        }
        asked.filters().forEach((key, value) -> {
            var filter = filters.get(key);
            if (filter == null) {
                throw new IllegalArgumentException(name + " has no filter " + key);
            }
            if (value != null && !(value instanceof String text && text.isBlank())) {
                conditions.add("(" + filter.sql() + ")");
                params.put(key, value);
            }
        });
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    private static boolean hasWhere(String where) {
        return !where.isEmpty();
    }

    /** A page of this list, each row as {@code mapper} makes it, its order and filters as asked. */
    public <T> Page<T> page(
            JdbcClient jdbc, Asked asked, @Nullable String cursor, @Nullable Integer limit, RowMapper<T> mapper) {
        int size = AdminPages.limit(limit);
        var order = order(asked.sort());
        var params = new LinkedHashMap<String, Object>();
        var where = where(asked, params);
        if (cursor != null && !cursor.isBlank()) {
            var position = Position.decode(cursor);
            if (!position.sort().equals(order.key())) {
                throw ApiProblem.invalid("cursor", "belongs to another order of this list.");
            }
            var keyset = "(" + order.sort().sql() + ", " + id + ") " + (order.descending() ? "<" : ">") + " (cast(:"
                    + SORT_VALUE + " as " + order.sort().type().cast + "), cast(:" + ROW_ID + " as " + idType.cast
                    + "))";
            where += (hasWhere(where) ? " and " : " where ") + keyset;
            params.put(SORT_VALUE, position.value());
            params.put(ROW_ID, position.id());
        }
        var direction = order.descending() ? " desc" : " asc";
        var sql = "select " + select + ", cast(" + order.sort().sql() + " as text) as " + SORT_VALUE + ", cast(" + id
                + " as text) as " + ROW_ID + " from " + from + where + " order by "
                + order.sort().sql() + direction
                + ", " + id + direction + " limit :console_limit";
        var positions = new ArrayList<Position>();
        var rows = jdbc.sql(sql)
                .params(params)
                .param("console_limit", size + 1)
                .query((rs, n) -> {
                    positions.add(new Position(order.key(), rs.getString(SORT_VALUE), rs.getString(ROW_ID)));
                    return mapper.mapRow(rs, n);
                })
                .list();
        if (rows.size() <= size) {
            return new Page<>(rows, null);
        }
        return new Page<>(
                List.copyOf(rows.subList(0, size)), positions.get(size - 1).encode());
    }

    /** How many rows it has, as asked. */
    public long count(JdbcClient jdbc, Asked asked) {
        var params = new LinkedHashMap<String, Object>();
        var where = where(asked, params);
        return jdbc.sql("select count(*) from " + from + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    /**
     * Each row's CSV values in the order of {@link #header}, up to {@code most} of them, from the database a batch at a
     * time rather than all held at once.
     */
    public void each(JdbcClient jdbc, Asked asked, int most, Consumer<List<@Nullable String>> row) {
        var order = order(asked.sort());
        var params = new LinkedHashMap<String, Object>();
        var where = where(asked, params);
        var columns = new ArrayList<String>();
        for (int i = 0; i < csv.size(); i++) {
            columns.add("cast(" + csv.get(i).sql() + " as text) as console_csv_" + i);
        }
        var direction = order.descending() ? " desc" : " asc";
        var sql = "select " + String.join(", ", columns) + " from " + from + where + " order by "
                + order.sort().sql() + direction + ", " + id + direction + " limit :console_limit";
        jdbc.sql(sql)
                .params(params)
                .param("console_limit", most)
                .withFetchSize(500)
                .query((RowCallbackHandler) rs -> {
                    var values = new ArrayList<@Nullable String>(csv.size());
                    for (int i = 0; i < csv.size(); i++) {
                        values.add(rs.getString(i + 1));
                    }
                    row.accept(values);
                });
    }

    private record Position(String sort, String value, String id) {

        String encode() {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString((sort + "\n" + id + "\n" + value).getBytes(StandardCharsets.UTF_8));
        }

        static Position decode(String cursor) {
            try {
                var raw = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
                var parts = raw.split("\n", 3);
                return new Position(parts[0], parts[2], parts[1]);
            } catch (RuntimeException notOurs) {
                throw ApiProblem.invalid("cursor", "isn't a cursor this API gave out.");
            }
        }
    }

    public static Builder of(String name, AdminPermission permission, boolean personalData) {
        return new Builder(name, permission, personalData);
    }

    /** What declares a list. */
    public static final class Builder {

        private final String name;
        private final AdminPermission permission;
        private final boolean personalData;
        private String select = "*";
        private String from = "";
        private String id = "id";
        private Type idType = Type.UUID;
        private String fixed = "";
        private String defaultSort = "";
        private boolean defaultDescending = true;
        private final Map<String, Sort> sorts = new LinkedHashMap<>();
        private final Map<String, Filter> filters = new LinkedHashMap<>();
        private final List<CsvColumn> csv = new ArrayList<>();

        private Builder(String name, AdminPermission permission, boolean personalData) {
            this.name = name;
            this.permission = permission;
            this.personalData = personalData;
        }

        /** What each row selects, as the module's mapper reads it. */
        public Builder select(String sql) {
            this.select = sql;
            return this;
        }

        public Builder from(String sql) {
            this.from = sql;
            return this;
        }

        /** The row's own id, a UUID: the last word in any order. */
        public Builder id(String sql) {
            this.id = sql;
            return this;
        }

        /** The row's own id when it isn't one UUID: {@code sql} is one value that's unique and comparable as {@code type}. */
        public Builder id(String sql, Type type) {
            this.id = sql;
            this.idType = type;
            return this;
        }

        /** What every row of this list is, whatever's asked. */
        public Builder always(String sql) {
            this.fixed = sql;
            return this;
        }

        /** Something it can be sorted by; {@code sql} must never be null, so give it a coalesce if it could be. */
        public Builder sort(String sortName, Type type, String sql) {
            sorts.put(sortName, new Sort(sql, type));
            return this;
        }

        /** Its order when none is asked for, which is one of its sorts. */
        public Builder sortedBy(String sortName, boolean descending) {
            this.defaultSort = sortName;
            this.defaultDescending = descending;
            return this;
        }

        public Builder filter(String filterName, String sql) {
            filters.put(filterName, new Filter(sql));
            return this;
        }

        public Builder csv(String columnName, String sql) {
            csv.add(new CsvColumn(columnName, sql));
            return this;
        }

        public AdminListing build() {
            if (!sorts.containsKey(defaultSort)) {
                throw new IllegalStateException(name + " has no default sort");
            }
            if (from.isEmpty() || csv.isEmpty()) {
                throw new IllegalStateException(name + " needs a table and a CSV");
            }
            return new AdminListing(this);
        }
    }
}
