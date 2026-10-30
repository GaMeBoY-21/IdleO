package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.OneAtATime;
import com.idleo.controlplane.hosts.HostRatings;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Renters rate the sessions they ran (ADR 0028), and hosts see the average. The rules, in one place:
 *
 * <ul>
 *   <li>only the session's renter rates it, and only once it has ended, having run for at least a minute;
 *   <li>a session has one rating, for the host that ran its last attempt, given within 7 days of the session ending
 *       and changed in place within 7 days of being given;
 *   <li>a host's average is over the ratings of its sessions that ended in the last 90 days, and shown only once there
 *       are 5 of them. It is worked out once a day ({@code trust.ratings.refresh-cron}), never on each rating, and
 *       its count is shown rounded down to a multiple of 5, so a host can't learn one renter's stars from how the
 *       figures move after a session. A host never sees who rated it or what they wrote.
 * </ul>
 */
@Service
class Ratings implements HostRatings {

    /** A rating can be given this long after its session ended, and changed this long after it was given. */
    static final Duration WINDOW = Duration.ofDays(7);

    /** A host's average is over its sessions that ended this recently. */
    static final Duration AVERAGE_OVER = Duration.ofDays(90);

    /** A host's average is shown only once it's over this many ratings. */
    static final int SHOWN_FROM = 5;

    static final int MAX_COMMENT = 500;

    private final EndedSessions sessions;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final OneAtATime oneAtATime;
    private final Clock clock;

    Ratings(
            EndedSessions sessions,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            OneAtATime oneAtATime,
            Clock clock) {
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.oneAtATime = oneAtATime;
        this.clock = clock;
    }

    /** A session's rating as its renter sees it. */
    record GivenRating(int stars, @Nullable String comment, Instant ratedAt, Instant changedAt) {}

    /**
     * Where a session stands: whether it can be rated or its rating changed now, until when, and its rating if it has
     * one.
     *
     * @param rateableUntil when giving or changing its rating closes; null if it can never be rated
     */
    record Standing(
            UUID sessionId,
            boolean rateable,
            @Nullable Instant rateableUntil,
            @Nullable GivenRating rating) {}

    /** What happened when a renter rated a session. */
    sealed interface Outcome {
        record Rated(Standing standing) implements Outcome {}

        /** Not their session, or it hasn't ended. */
        record NotFound() implements Outcome {}

        /** It didn't run for a minute: there's nothing to rate. */
        record NotRateable() implements Outcome {}

        /** Too late: more than 7 days after it ended, or after the rating was given. */
        record Closed(Instant closedAt) implements Outcome {}
    }

    /** The renter's session's standing; empty if it isn't theirs or hasn't ended. */
    Optional<Standing> standing(UUID renterId, UUID sessionId) {
        return sessions.ofRenter(renterId, sessionId).map(session -> standing(session, find(sessionId)));
    }

    /** The session's rating, for an admin; null if it has none. */
    @Nullable
    GivenRating given(UUID sessionId) {
        return find(sessionId);
    }

    /** Gives the session its rating, or changes the one it has. */
    Outcome rate(UUID renterId, UUID sessionId, int stars, @Nullable String comment) {
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("a rating is 1 to 5 stars");
        }
        var text = comment == null || comment.isBlank() ? null : comment.strip();
        if (text != null && text.length() > MAX_COMMENT) {
            throw new IllegalArgumentException("a comment is at most " + MAX_COMMENT + " characters");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            var session = sessions.lockOfRenter(renterId, sessionId).orElse(null);
            if (session == null) {
                return new Outcome.NotFound();
            }
            if (!session.ranAMinute()) {
                return new Outcome.NotRateable();
            }
            var existing = find(sessionId);
            var until = until(session, existing);
            var now = now();
            if (!now.isBefore(until)) {
                return new Outcome.Closed(until);
            }
            if (existing == null) {
                jdbc.sql("""
                                insert into trust.ratings
                                    (session_id, renter_id, host_id, stars, comment, rated_at, changed_at)
                                values (:sessionId, :renterId, :hostId, :stars, :comment, :now, :now)
                                """)
                        .param("sessionId", sessionId)
                        .param("renterId", renterId)
                        .param("hostId", session.hostId())
                        .param("stars", stars)
                        .param("comment", text)
                        .param("now", Timestamp.from(now))
                        .update();
            } else {
                jdbc.sql("""
                                update trust.ratings set stars = :stars, comment = :comment, changed_at = :now
                                where session_id = :sessionId
                                """)
                        .param("stars", stars)
                        .param("comment", text)
                        .param("now", Timestamp.from(now))
                        .param("sessionId", sessionId)
                        .update();
            }
            return new Outcome.Rated(standing(session, find(sessionId)));
        }));
    }

    @Override
    public Map<UUID, HostRatings.Rating> of(Collection<UUID> hostIds) {
        var rated = new HashMap<UUID, HostRatings.Rating>();
        if (hostIds.isEmpty()) {
            return rated;
        }
        jdbc.sql("""
                        select host_id, average_stars, count_at_least from trust.host_ratings
                        where host_id in (:hostIds)
                        """).param("hostIds", hostIds).query(rs -> {
            rated.put(
                    rs.getObject("host_id", UUID.class),
                    new HostRatings.Rating(rs.getBigDecimal("average_stars"), rs.getInt("count_at_least")));
        });
        return rated;
    }

    /** The daily refresh of what hosts see (ADR 0028). */
    @Scheduled(cron = "${trust.ratings.refresh-cron}", zone = "${trust.ratings.zone}")
    void daily() {
        // One instance at a time (ADR 0047): two refreshes at once would each insert every host's row.
        oneAtATime.run("trust:host-ratings", this::refreshHostRatings);
    }

    /**
     * Works out every host's shown rating afresh, in one transaction: the mean of its last 90 days' ratings to one
     * decimal, and their count rounded down to a multiple of 5, for each host with at least 5.
     */
    void refreshHostRatings() {
        var now = Timestamp.from(Instant.now(clock));
        transactions.executeWithoutResult(status -> {
            jdbc.sql("delete from trust.host_ratings").update();
            jdbc.sql("""
                            insert into trust.host_ratings (host_id, average_stars, count_at_least, refreshed_at)
                            select r.host_id, round(avg(r.stars), 1), (count(*) / 5) * 5, :now
                            from trust.ratings r join trust.ended_sessions s on s.session_id = r.session_id
                            where s.ended_at >= :since
                            group by r.host_id
                            having count(*) >= :shownFrom
                            """)
                    .param("now", now)
                    .param("since", Timestamp.from(Instant.now(clock).minus(AVERAGE_OVER)))
                    .param("shownFrom", SHOWN_FROM)
                    .update();
        });
    }

    private Standing standing(EndedSessions.EndedSession session, @Nullable GivenRating rating) {
        if (!session.ranAMinute()) {
            return new Standing(session.sessionId(), false, null, rating);
        }
        var until = until(session, rating);
        return new Standing(session.sessionId(), Instant.now(clock).isBefore(until), until, rating);
    }

    /** A rating is given within 7 days of the session ending, and changed within 7 days of being given. */
    private static Instant until(EndedSessions.EndedSession session, @Nullable GivenRating rating) {
        return (rating == null ? session.endedAt() : rating.ratedAt()).plus(WINDOW);
    }

    private @Nullable GivenRating find(UUID sessionId) {
        return jdbc.sql("select stars, comment, rated_at, changed_at from trust.ratings where session_id = :sessionId")
                .param("sessionId", sessionId)
                .query((rs, n) -> new GivenRating(
                        rs.getInt("stars"),
                        rs.getString("comment"),
                        rs.getTimestamp("rated_at").toInstant(),
                        rs.getTimestamp("changed_at").toInstant()))
                .optional()
                .orElse(null);
    }

    /** Now, to the microsecond PostgreSQL stores, so a returned time equals the stored one. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
