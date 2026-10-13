package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.billing.SessionRefunds;
import com.idleo.controlplane.sessions.MeteredSessions;
import com.idleo.controlplane.sessions.SessionCosts;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What a session has cost so far (ADR 0017): charged, accrued, whether its credit is low, and whether it's closed.
 * Charged is net of everything given back: lost time, and refunds for reports (ADR 0028). The spending cap counts
 * charges before report refunds ({@link Settlements#chargedNet}), so a refund never lets a session be charged more.
 */
@Component
class Costs implements SessionCosts {

    private final MeteredSessions sessions;
    private final Settlements settlements;
    private final SessionRefunds refunds;
    private final JdbcClient jdbc;

    Costs(MeteredSessions sessions, Settlements settlements, SessionRefunds refunds, JdbcClient jdbc) {
        this.sessions = sessions;
        this.settlements = settlements;
        this.refunds = refunds;
        this.jdbc = jdbc;
    }

    @Override
    public Cost of(UUID sessionId) {
        record Row(boolean lowCredit, boolean closed) {}
        var row = jdbc.sql("""
                        select low_credit, closed_at is not null as closed from metering.metered_sessions
                        where session_id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Row(rs.getBoolean("low_credit"), rs.getBoolean("closed")))
                .optional()
                .orElse(new Row(false, false));
        var charged = settlements.chargedNet(sessionId).minus(refunds.refunded(sessionId));
        return new Cost(charged.value(), accruedPaise(sessionId), row.lowCredit(), row.closed());
    }

    /** What both witnesses saw that isn't settled yet, at each minute's attempt's price. */
    long accruedPaise(UUID sessionId) {
        var prices = new HashMap<Integer, Integer>();
        sessions.attempts(sessionId).forEach(attempt -> prices.put(attempt.attempt(), attempt.pricePaisePerMin()));
        long accrued = jdbc.sql("""
                        select agent_attempt from metering.usage_minutes
                        where session_id = :sessionId and agent_reported and lease_alive
                          and agent_attempt = lease_attempt and not billed and not waived
                        """).param("sessionId", sessionId).query(Integer.class).list().stream()
                .mapToLong(attempt -> prices.getOrDefault(attempt, 0))
                .sum();
        return accrued;
    }
}
