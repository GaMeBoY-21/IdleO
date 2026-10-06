package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.sessions.MeteredSessions;
import com.idleo.controlplane.sessions.SessionCosts;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** What a session has cost so far (ADR 0017): charged, accrued, and whether its credit is low. */
@Component
class Costs implements SessionCosts {

    private final MeteredSessions sessions;
    private final Settlements settlements;
    private final JdbcClient jdbc;

    Costs(MeteredSessions sessions, Settlements settlements, JdbcClient jdbc) {
        this.sessions = sessions;
        this.settlements = settlements;
        this.jdbc = jdbc;
    }

    @Override
    public Cost of(UUID sessionId) {
        var prices = new HashMap<Integer, Integer>();
        sessions.attempts(sessionId).forEach(attempt -> prices.put(attempt.attempt(), attempt.pricePaisePerMin()));
        long accrued = jdbc.sql("""
                        select agent_attempt from metering.usage_minutes
                        where session_id = :sessionId and agent_reported and lease_alive
                          and agent_attempt = lease_attempt and not billed and not waived
                        """).param("sessionId", sessionId).query(Integer.class).list().stream()
                .mapToLong(attempt -> prices.getOrDefault(attempt, 0))
                .sum();
        boolean lowCredit = jdbc.sql("select low_credit from metering.metered_sessions where session_id = :sessionId")
                .param("sessionId", sessionId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
        return new Cost(settlements.chargedNet(sessionId).value(), accrued, lowCredit);
    }
}
