package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.identity.DashboardFigures;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The metering module's figures for the admin dashboard (ADR 0027): the settlement backlog (open sessions with a
 * window more than a minute past due, or ended and not closed a minute after the wait for late reports), and the
 * minutes in the review queue nobody has decided.
 */
@Component
class MeteringFigures implements DashboardFigures {

    static final Duration GRACE = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration reviewAfter;

    MeteringFigures(JdbcClient jdbc, Clock clock, @Value("${metering.review-after}") Duration reviewAfter) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.reviewAfter = reviewAfter;
    }

    @Override
    public Map<Figure, Long> figures() {
        var now = clock.instant();
        return jdbc.sql("""
                        select (select count(*) from metering.metered_sessions
                                where closed_at is null
                                  and (next_window_at < :windowDue or ended_at < :closeDue)) as backlog,
                               (select count(*) from metering.review_minutes where resolution is null) as review
                        """)
                .param("windowDue", Timestamp.from(now.minus(GRACE)))
                .param("closeDue", Timestamp.from(now.minus(reviewAfter).minus(GRACE)))
                .query((rs, n) -> Map.of(
                        Figure.SETTLEMENT_BACKLOG, rs.getLong("backlog"), Figure.REVIEW_QUEUE, rs.getLong("review")))
                .single();
    }
}
