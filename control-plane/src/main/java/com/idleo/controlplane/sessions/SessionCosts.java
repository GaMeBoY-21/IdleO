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
     */
    record Cost(long chargedPaise, long accruedPaise, boolean lowCredit) {}

    Cost of(UUID sessionId);
}
