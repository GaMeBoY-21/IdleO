package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminPages;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ratings with their comments, for the admin console (ADR 0028): the only place a comment is ever shown. Newest
 * session first.
 */
@RestController
@RequestMapping("/admin/v1/ratings")
@ConditionalOnWebApplication
class AdminRatingsController {

    private final JdbcClient jdbc;

    AdminRatingsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One rating: the session, who gave it, the host it's for, the stars and what they wrote. */
    record AdminRating(
            UUID sessionId,
            UUID renterId,
            UUID hostId,
            int stars,
            @Nullable String comment,
            Instant ratedAt,
            Instant changedAt) {}

    record AdminRatingPage(
            List<AdminRating> ratings, @Nullable String nextCursor) {}

    /** Filters: {@code host} the host rated, {@code session} one session's. */
    @GetMapping
    AdminRatingPage list(
            @RequestParam(required = false) @Nullable UUID host,
            @RequestParam(required = false) @Nullable UUID session,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        var rows = jdbc.sql("""
                        select session_id, renter_id, host_id, stars, comment, rated_at, changed_at from trust.ratings
                        where (cast(:host as uuid) is null or host_id = cast(:host as uuid))
                          and (cast(:session as uuid) is null or session_id = cast(:session as uuid))
                          and (cast(:after as uuid) is null or session_id < cast(:after as uuid))
                        order by session_id desc
                        limit :limit
                        """)
                .param("host", host)
                .param("session", session)
                .param("after", AdminPages.after(cursor))
                .param("limit", size + 1)
                .query((rs, n) -> new AdminRating(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("renter_id", UUID.class),
                        rs.getObject("host_id", UUID.class),
                        rs.getInt("stars"),
                        rs.getString("comment"),
                        rs.getTimestamp("rated_at").toInstant(),
                        rs.getTimestamp("changed_at").toInstant()))
                .list();
        var page = rows.size() > size ? rows.subList(0, size) : rows;
        return new AdminRatingPage(
                List.copyOf(page),
                rows.size() > size ? AdminPages.cursorAfter(page.getLast().sessionId()) : null);
    }
}
