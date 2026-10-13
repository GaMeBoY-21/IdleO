package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.identity.DashboardFigures;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The sessions module's figures for the admin dashboard (ADR 0027): sessions running now (stopping ones too), and
 * sessions booked since midnight in India.
 */
@Component
class SessionFigures implements DashboardFigures {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final JdbcClient jdbc;
    private final Clock clock;

    SessionFigures(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Map<Figure, Long> figures() {
        var midnight =
                LocalDate.ofInstant(clock.instant(), INDIA).atStartOfDay(INDIA).toInstant();
        return jdbc.sql("""
                        select count(*) filter (where state in ('RUNNING', 'FINISHING')) as running,
                               count(*) filter (where created_at >= :midnight) as today
                        from sessions.sessions
                        """)
                .param("midnight", Timestamp.from(midnight))
                .query((rs, n) -> Map.of(
                        Figure.SESSIONS_RUNNING, rs.getLong("running"),
                        Figure.SESSIONS_TODAY, rs.getLong("today")))
                .single();
    }
}
