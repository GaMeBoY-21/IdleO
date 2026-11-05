package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.RequiresPermission;
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

    /** The list, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("ratings", AdminPermission.RATINGS_READ, true)
            .select("session_id, renter_id, host_id, stars, comment, rated_at, changed_at")
            .from("trust.ratings")
            .id("session_id")
            .sort("session", AdminListing.Type.UUID, "session_id")
            .sort("stars", AdminListing.Type.NUMBER, "stars")
            .sortedBy("session", true)
            .filter("host", "host_id = cast(:host as uuid)")
            .filter("session", "session_id = cast(:session as uuid)")
            .filter("tagged", "renter_id in (:tagged)")
            .csv("sessionId", "session_id")
            .csv("renterId", "renter_id")
            .csv("hostId", "host_id")
            .csv("stars", "stars")
            .csv("comment", "comment")
            .csv("ratedAt", "rated_at")
            .build();

    private final JdbcClient jdbc;
    private final AdminTagged tagged;

    AdminRatingsController(JdbcClient jdbc, AdminTagged tagged) {
        this.tagged = tagged;
        this.jdbc = jdbc;
    }

    /** One rating: the session, who gave it, the host it's for, the stars and what they wrote. */
    record AdminRating(
            UUID sessionId,
            @Nullable UUID renterId,
            UUID hostId,
            int stars,
            @Nullable String comment,
            Instant ratedAt,
            Instant changedAt) {}

    record AdminRatingPage(
            List<AdminRating> ratings, @Nullable String nextCursor) {}

    /** Filters: {@code host} the host rated, {@code session} one session's. */
    @RequiresPermission(AdminPermission.RATINGS_READ)
    @AdminList("ratings")
    @GetMapping
    AdminRatingPage list(
            @RequestParam(required = false) @Nullable UUID host,
            @RequestParam(required = false) @Nullable UUID session,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(sort, "host", host, "session", session, "tagged", tagged.subjects("user", tag)),
                cursor,
                limit,
                (rs, n) -> new AdminRating(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("renter_id", UUID.class),
                        rs.getObject("host_id", UUID.class),
                        rs.getInt("stars"),
                        rs.getString("comment"),
                        rs.getTimestamp("rated_at").toInstant(),
                        rs.getTimestamp("changed_at").toInstant()));
        return new AdminRatingPage(page.rows(), page.nextCursor());
    }
}
