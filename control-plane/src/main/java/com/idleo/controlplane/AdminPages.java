package com.idleo.controlplane;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Paging for the admin console's lists (ADR 0027), the same in every module: newest first by id (a UUIDv7, so by
 * time), {@code limit} rows a page, and an opaque {@code cursor} that is the previous page's {@code nextCursor}.
 */
public final class AdminPages {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private static final String PREFIX = "a:";

    private AdminPages() {}

    /** The cursor for the page after the row with this id. */
    public static String cursorAfter(UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((PREFIX + id).getBytes(StandardCharsets.UTF_8));
    }

    /** The id a cursor points after; null for the first page. */
    public static @Nullable UUID after(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var raw = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
            if (raw.startsWith(PREFIX)) {
                return UUID.fromString(raw.substring(PREFIX.length()));
            }
        } catch (IllegalArgumentException e) {
            // Refused below.
        }
        throw ApiProblem.invalid("cursor", "isn't a cursor this API gave out.");
    }

    /** The page size asked for: 1 to {@value #MAX_LIMIT}, {@value #DEFAULT_LIMIT} when not given. */
    public static int limit(@Nullable Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiProblem.invalid("limit", "must be from 1 to " + MAX_LIMIT + ".");
        }
        return limit;
    }
}
