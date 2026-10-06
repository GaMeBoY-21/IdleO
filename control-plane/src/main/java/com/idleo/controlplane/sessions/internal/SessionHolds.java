package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.billing.CreditReservations;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who releases a session's hold when it ends (ADR 0017). A session that never ran has nothing to charge, so its
 * hold goes at once. One that ran, even if only on an earlier attempt, is released by metering when it closes the
 * session's usage, after the final settlement has charged from the hold.
 */
@Component
class SessionHolds {

    private final CreditReservations reservations;
    private final JdbcClient jdbc;

    SessionHolds(CreditReservations reservations, JdbcClient jdbc) {
        this.reservations = reservations;
        this.jdbc = jdbc;
    }

    boolean hasRun(UUID sessionId) {
        return jdbc.sql("select started_at is not null from sessions.sessions where id = :id")
                .param("id", sessionId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /** At an end: releases the hold unless the session ran, when metering releases it at close. */
    @Transactional(propagation = Propagation.MANDATORY)
    void releaseUnlessMetered(UUID sessionId) {
        if (!hasRun(sessionId)) {
            reservations.release(sessionId);
        }
    }
}
