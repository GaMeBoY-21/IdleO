package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.AccountLimits;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A cap on session hours a week, from the enforcement ladder (ADR 0053): past it, a new session is refused. */
@Component
class SessionHourCap {

    private final AccountLimits limits;
    private final JdbcClient jdbc;
    private final Clock clock;

    SessionHourCap(AccountLimits limits, JdbcClient jdbc, Clock clock) {
        this.limits = limits;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void check(UUID renter) {
        var cap = limits.sessionHoursCap(renter);
        if (cap.isEmpty()) {
            return;
        }
        var now = clock.instant();
        var seconds = jdbc.sql("""
                        select coalesce(sum(extract(epoch from
                            case when state = 'RUNNING' then cast(:now as timestamptz) else state_changed_at end
                            - started_at)), 0)
                        from sessions.sessions
                        where renter_id = :renter and started_at >= :since
                        """)
                .param("now", Timestamp.from(now))
                .param("renter", renter)
                .param("since", Timestamp.from(now.minus(Duration.ofDays(7))))
                .query(Double.class)
                .single();
        if (seconds >= cap.getAsInt() * 3600.0) {
            throw ApiProblem.of(
                    Problem.ACTION_REFUSED,
                    "Your account is limited to " + cap.getAsInt() + " session hours a week, and you've reached it.");
        }
    }
}
