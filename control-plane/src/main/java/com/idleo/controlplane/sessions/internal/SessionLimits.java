package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.UserDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The renter's session limits, checked as a session is booked:
 *
 * <ul>
 *   <li>{@code sessions.limits.new-per-hour} new sessions in any hour, and {@code new-per-hour-first-day} for an
 *       account less than a day old: then 429 {@code too-many-new-sessions}, with {@code Retry-After} the time until
 *       the oldest of them is an hour old;
 *   <li>{@code sessions.limits.at-once} sessions that haven't ended: then 409 {@code too-many-sessions}.
 * </ul>
 *
 * <p>Both are counted from the sessions themselves, in the booking's own transaction, under a lock on the renter, so
 * two bookings at once can't both slip under a limit, a retried booking (the same Idempotency-Key) is never counted
 * twice, and nothing depends on Valkey.
 */
@Component
@EnableConfigurationProperties(SessionLimitProperties.class)
class SessionLimits {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration FIRST_DAY = Duration.ofDays(1);
    private static final List<String> FINAL_STATES = Arrays.stream(SessionState.values())
            .filter(state -> state.isFinal)
            .map(Enum::name)
            .toList();

    private final JdbcClient jdbc;
    private final UserDirectory users;
    private final SessionLimitProperties limits;
    private final Clock clock;

    SessionLimits(JdbcClient jdbc, UserDirectory users, SessionLimitProperties limits, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.limits = limits;
        this.clock = clock;
    }

    /** Refuses the booking when it would pass a limit; holds the renter's lock until the booking commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    void checkNewSession(UUID renterId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:renter, 0))")
                .param("renter", "sessions.limits:" + renterId)
                .query(Object.class)
                .single();
        var now = Instant.now(clock);
        var unfinished = jdbc.sql("""
                        select count(*) from sessions.sessions
                        where renter_id = :renter and state not in (:final)
                        """)
                .param("renter", renterId)
                .param("final", FINAL_STATES)
                .query(Integer.class)
                .single();
        if (unfinished >= limits.atOnce()) {
            throw ApiProblem.of(
                    Problem.TOO_MANY_SESSIONS,
                    "You have " + unfinished + " sessions that haven't ended, the most at once. Wait for one to "
                            + "finish, or stop one.");
        }
        var accountAge = users.createdAt(renterId).map(created -> Duration.between(created, now));
        int perHour = accountAge.map(age -> age.compareTo(FIRST_DAY) < 0).orElse(true)
                ? limits.newPerHourFirstDay()
                : limits.newPerHour();
        var recent = jdbc.sql("""
                        select created_at from sessions.sessions
                        where renter_id = :renter and created_at > :since
                        order by created_at
                        """)
                .param("renter", renterId)
                .param("since", Timestamp.from(now.minus(HOUR)))
                .query((rs, row) -> rs.getTimestamp("created_at").toInstant())
                .list();
        if (recent.size() >= perHour) {
            var nextFree = recent.get(recent.size() - perHour).plus(HOUR);
            throw ApiProblem.of(
                            Problem.TOO_MANY_NEW_SESSIONS,
                            "You've booked " + recent.size() + " sessions in the last hour, the most allowed"
                                    + (perHour == limits.newPerHourFirstDay() && perHour != limits.newPerHour()
                                            ? " for an account less than a day old"
                                            : "")
                                    + ". Try again later.")
                    .retryAfter(Duration.between(now, nextFree));
        }
    }
}
