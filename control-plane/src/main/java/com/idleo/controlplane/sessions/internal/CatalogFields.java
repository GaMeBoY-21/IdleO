package com.idleo.controlplane.sessions.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.convert.DurationStyle;

/**
 * Reading one map of the template catalog: each helper refuses a missing or malformed field with a message that names
 * where it is ({@code where}, such as "Template smoke, input model"), so a bad catalog stops the start with a clear
 * reason.
 */
final class CatalogFields {

    private CatalogFields() {}

    /** Refuses any field not in {@code allowed}. */
    static void known(Map<?, ?> fields, Set<String> allowed, String where) {
        for (var key : fields.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new IllegalStateException(where + ": unknown field " + key + ".");
            }
        }
    }

    /** Refuses {@code name} unless it's {@code allowed} here. */
    static void only(Map<?, ?> fields, String name, boolean allowed, String where, String forWhat) {
        if (!allowed && fields.containsKey(name)) {
            throw new IllegalStateException(where + ": " + name + " is only for " + forWhat + ".");
        }
    }

    static boolean flag(Map<?, ?> fields, String name, String where) {
        var value = fields.get(name);
        if (value != null && !(value instanceof Boolean)) {
            throw new IllegalStateException(where + ": " + name + " must be true or false.");
        }
        return Boolean.TRUE.equals(value);
    }

    static @Nullable BigDecimal decimal(Map<?, ?> fields, String name, String where) {
        var value = fields.get(name);
        return switch (value) {
            case null -> null;
            case Integer whole -> BigDecimal.valueOf(whole);
            case Long whole -> BigDecimal.valueOf(whole);
            case java.math.BigInteger whole -> new BigDecimal(whole);
            case Double real -> BigDecimal.valueOf(real);
            default -> throw new IllegalStateException(where + ": " + name + " must be a number.");
        };
    }

    /** A required, non-blank string, stripped. */
    static String text(Map<?, ?> fields, String name, String where) {
        if (!(fields.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalStateException(where + ": " + name + " is required.");
        }
        return value.strip();
    }

    /** An optional string: null when it's left out, refused when it's there but blank. */
    static @Nullable String optionalText(Map<?, ?> fields, String name, String where) {
        return fields.get(name) == null ? null : text(fields, name, where);
    }

    static int positive(Map<?, ?> fields, String name, String where) {
        if (!(fields.get(name) instanceof Integer value) || value <= 0) {
            throw new IllegalStateException(where + ": " + name + " must be a whole number above zero.");
        }
        return value;
    }

    static Duration duration(Map<?, ?> fields, String name, String where) {
        var text = text(fields, name, where);
        Duration value;
        try {
            value = DurationStyle.detectAndParse(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": " + name + " isn't a duration like 30m.");
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalStateException(where + ": " + name + " must be above zero.");
        }
        return value;
    }

    /** A list of maps, each checked by the caller; empty when the field is left out. */
    static List<Map<?, ?>> maps(Map<?, ?> fields, String name, String where) {
        var value = fields.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> entries)) {
            throw new IllegalStateException(where + ": " + name + " must be a list.");
        }
        var maps = new java.util.ArrayList<Map<?, ?>>();
        for (var entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalStateException(where + ": every entry of " + name + " must be a map.");
            }
            maps.add(map);
        }
        return maps;
    }

    /** A list of plain values, as strings; empty when the field is left out. */
    static List<String> strings(Map<?, ?> fields, String name, String where) {
        var value = fields.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> entries)) {
            throw new IllegalStateException(where + ": " + name + " must be a list.");
        }
        return entries.stream().map(String::valueOf).toList();
    }
}
