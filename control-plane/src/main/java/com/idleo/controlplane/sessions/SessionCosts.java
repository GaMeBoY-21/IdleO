package com.idleo.controlplane.sessions;

import java.util.UUID;

/**
 * A port the sessions module declares and the metering module implements (ADR 0017): what a session has cost so
 * far, for the renter's view. Metering already depends on sessions, so this keeps the dependency one way.
 */
public interface SessionCosts {

    /**
     * @param chargedPaise settled, less anything given back
     * @param accruedPaise billable by both witnesses but not settled yet
     * @param lowCredit the hold plus the renter's available balance covers under 10 minutes
     * @param closed metering has closed the session's usage: its last settlement is posted and its hold released
     */
    record Cost(long chargedPaise, long accruedPaise, boolean lowCredit, boolean closed) {

        /** A session metering never followed: one that never ran. */
        public static final Cost NONE = new Cost(0, 0, false, false);
    }

    Cost of(UUID sessionId);
}
