package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.ControlRoom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The metering module's checks for the live dashboard (ADR 0056): the settlement backlog (open sessions with a window
 * more than a minute past due, or ended and not closed a minute after the wait for late reports), and the minutes in the
 * review queue nobody has decided.
 */
@Component
class MeteringControlRoom implements ControlRoom.HealthSource {

    static final Duration GRACE = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final Duration reviewAfter;

    MeteringControlRoom(JdbcClient jdbc, @Value("${metering.review-after}") Duration reviewAfter) {
        this.jdbc = jdbc;
        this.reviewAfter = reviewAfter;
    }

    @Override
    public List<ControlRoom.Check> checks(Instant now) {
        return jdbc.sql("""
                        select (select count(*) from metering.metered_sessions
                                where closed_at is null
                                  and (next_window_at < :windowDue or ended_at < :closeDue)) as backlog,
                               (select count(*) from metering.review_minutes where resolution is null) as review
                        """)
                .param("windowDue", Timestamp.from(now.minus(GRACE)))
                .param("closeDue", Timestamp.from(now.minus(reviewAfter).minus(GRACE)))
                .query((rs, n) -> {
                    long backlog = rs.getLong("backlog");
                    long review = rs.getLong("review");
                    return List.of(
                            new ControlRoom.Check(
                                    "settlement backlog",
                                    backlog == 0,
                                    backlog == 0
                                            ? "every session settled on time"
                                            : backlog + " session" + (backlog == 1 ? "" : "s") + " past due to settle"),
                            new ControlRoom.Check(
                                    "review queue",
                                    true,
                                    review + " minute" + (review == 1 ? "" : "s") + " waiting for a decision"));
                })
                .single();
    }
}
