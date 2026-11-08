package com.idleo.controlplane;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /** How a row becomes something a bulk action can act on. */
    private record BulkFrom(String kind, String subject, String person, String title) {}

    /**
     * One row, as a bulk action acts on it.
     *
     * @param kind what a tag on it is a tag on: {@code user}, {@code host}, {@code session} or {@code payment}
     * @param subject the thing of that kind
     * @param person who a warning goes to
     */
    public record BulkTarget(UUID id, String title, String kind, UUID subject, UUID person) {}

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

    private static final String SORT_VALUE = "console_sort_value_";
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
    private final @Nullable BulkFrom bulk;
    private final Set<String> bulkActions;
    private final @Nullable String noBulk;

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
        this.bulk = builder.bulk;
        this.bulkActions = Set.copyOf(builder.bulkActions);
        this.noBulk = builder.noBulk;
    }

    /** What a bulk action may do from this list (ADR 0057); empty when {@link #noBulkBecause} says why not. */
    public Set<String> bulkActions() {
        return bulkActions;
    }

    /** Why this list has no bulk actions, or null when it has. */
    public @Nullable String noBulkBecause() {
        return noBulk;
    }

    /** These rows, as a bulk action acts on them; a row that isn't in the list isn't among them. */
    public List<BulkTarget> bulkTargets(JdbcClient jdbc, Collection<UUID> ids) {
        if (bulk == null) {
            throw new IllegalStateException(name + " has no bulk targets");
        }
        var shape = bulk;
        return jdbc.sql("select cast(" + id + " as uuid) as bulk_id, " + shape.title() + " as bulk_title, cast("
                        + shape.subject() + " as uuid) as bulk_subject, cast(" + shape.person()
                        + " as uuid) as bulk_person from " + from + " where " + id + " in (:bulk_ids)"
                        + (fixed.isEmpty() ? "" : " and (" + fixed + ")"))
                .param("bulk_ids", ids)
                .query((rs, n) -> new BulkTarget(
                        rs.getObject("bulk_id", UUID.class),
                        rs.getString("bulk_title"),
                        shape.kind(),
                        rs.getObject("bulk_subject", UUID.class),
                        rs.getObject("bulk_person", UUID.class)))
                .list();
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

    /** One column of an order. */
    private record Part(String key, Sort sort, boolean descending) {

        String sql() {
            return sort.sql() + (descending ? " desc" : " asc");
        }
    }

    /**
     * An order: one sort, or several by priority (ADR 0057). {@code key} is what a cursor belongs to. The row's id breaks
     * ties in the first sort's direction.
     */
    private record Order(String key, List<Part> parts) {

        boolean descending() {
            return parts.getFirst().descending();
        }

        String sql(String id) {
            return String.join(", ", parts.stream().map(Part::sql).toList()) + ", " + id
                    + (descending() ? " desc" : " asc");
        }
    }

    /** The most sorts one order may hold. */
    private static final int MOST_SORTS = 3;

    private Order order(@Nullable String asked) {
        if (asked == null || asked.isBlank()) {
            return new Order(defaultSort, List.of(new Part(defaultSort, sorts.get(defaultSort), defaultDescending)));
        }
        var parts = new ArrayList<Part>();
        for (var one : asked.strip().split(",")) {
            var words = one.strip().split(":", 2);
            var sort = sorts.get(words[0]);
            if (sort == null) {
                throw ApiProblem.invalid(
                        "sort", "can't sort by that. It can be: " + String.join(", ", sorts.keySet()) + ".");
            }
            if (words.length == 2 && !words[1].equals("asc") && !words[1].equals("desc")) {
                throw ApiProblem.invalid("sort", "must be name:asc or name:desc.");
            }
            if (parts.stream().anyMatch(part -> part.key().equals(words[0]))) {
                throw ApiProblem.invalid("sort", "names " + words[0] + " twice.");
            }
            parts.add(new Part(words[0], sort, words.length != 2 || words[1].equals("desc")));
        }
        if (parts.size() > MOST_SORTS) {
            throw ApiProblem.invalid("sort", "can have at most " + MOST_SORTS + " columns.");
        }
        return new Order(
                parts.size() == 1
                        ? parts.getFirst().key()
                        : String.join(
                                ",",
                                parts.stream()
                                        .map(part -> part.key() + ":" + (part.descending() ? "desc" : "asc"))
                                        .toList()),
                List.copyOf(parts));
    }

    /**
     * What comes after a position. One sort compares a row of values, which its index can answer; several in mixed
     * directions spell it out, column by column.
     */
    private String after(Order order, Position position, Map<String, Object> params) {
        params.put(ROW_ID, position.id());
        var idValue = "cast(:" + ROW_ID + " as " + idType.cast + ")";
        var values = position.values();
        if (values.size() != order.parts().size()) {
            throw ApiProblem.invalid("cursor", "belongs to another order of this list.");
        }
        if (order.parts().size() == 1) {
            var only = order.parts().getFirst();
            params.put(SORT_VALUE + 0, values.getFirst());
            return "(" + only.sort().sql() + ", " + id + ") " + (only.descending() ? "<" : ">") + " (cast(:"
                    + SORT_VALUE + "0 as " + only.sort().type().cast + "), " + idValue + ")";
        }
        var condition = id + (order.descending() ? " < " : " > ") + idValue;
        for (int i = order.parts().size() - 1; i >= 0; i--) {
            var part = order.parts().get(i);
            params.put(SORT_VALUE + i, values.get(i));
            var value = "cast(:" + SORT_VALUE + i + " as " + part.sort().type().cast + ")";
            condition = "(" + part.sort().sql() + (part.descending() ? " < " : " > ") + value + " or ("
                    + part.sort().sql() + " = " + value + " and (" + condition + ")))";
        }
        return condition;
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
            where += (hasWhere(where) ? " and " : " where ") + after(order, position, params);
        }
        var sortValues = new StringBuilder();
        for (int i = 0; i < order.parts().size(); i++) {
            sortValues
                    .append("cast(")
                    .append(order.parts().get(i).sort().sql())
                    .append(" as text) as ")
                    .append(SORT_VALUE)
                    .append(i)
                    .append(", ");
        }
        var sql = "select " + select + ", " + sortValues + "cast(" + id + " as text) as " + ROW_ID + " from " + from
                + where + " order by " + order.sql(id) + " limit :console_limit";
        var positions = new ArrayList<Position>();
        var rows = jdbc.sql(sql)
                .params(params)
                .param("console_limit", size + 1)
                .query((rs, n) -> {
                    var values = new ArrayList<String>(order.parts().size());
                    for (int i = 0; i < order.parts().size(); i++) {
                        values.add(rs.getString(SORT_VALUE + i));
                    }
                    positions.add(new Position(order.key(), values, rs.getString(ROW_ID)));
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
        var sql = "select " + String.join(", ", columns) + " from " + from + where + " order by " + order.sql(id)
                + " limit :console_limit";
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

    /** Where a page ended. Several values are joined by a character a database text can't hold. */
    private record Position(String sort, List<String> values, String id) {

        private static final String BETWEEN = "\0";

        String encode() {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            (sort + "\n" + id + "\n" + String.join(BETWEEN, values)).getBytes(StandardCharsets.UTF_8));
        }

        static Position decode(String cursor) {
            try {
                var raw = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
                var parts = raw.split("\n", 3);
                return new Position(parts[0], List.of(parts[2].split(BETWEEN, -1)), parts[1]);
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
        private @Nullable BulkFrom bulk;
        private Set<String> bulkActions = Set.of();
        private @Nullable String noBulk;
        private boolean bulkDeclared;

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

        /**
         * What a bulk action may do from this list (ADR 0057): its row as {@code kind}'s {@code subject}, who a warning
         * goes to and a title with no personal data, all in SQL.
         */
        public Builder bulk(String kind, String subject, String person, String title, String... actions) {
            this.bulk = new BulkFrom(kind, subject, person, title);
            this.bulkActions = Set.of(actions);
            this.bulkDeclared = true;
            return this;
        }

        /** The same for a list whose module reads its own targets: the inbox. */
        public Builder bulkOfItsOwn(String... actions) {
            this.bulkActions = Set.of(actions);
            this.bulkDeclared = true;
            return this;
        }

        /** That this list has no bulk actions, and why. */
        public Builder noBulk(String why) {
            this.noBulk = why;
            this.bulkDeclared = true;
            return this;
        }

        public AdminListing build() {
            if (!bulkDeclared) {
                throw new IllegalStateException(name + " must say what bulk actions it allows, or why it has none");
            }
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
