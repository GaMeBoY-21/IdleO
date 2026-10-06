package com.idleo.controlplane.sessions;

import java.util.UUID;

/** What metering may do to a running session (ADR 0017): stop it when its credit or spending cap runs out. */
public interface SessionControl {

    /** Why a running session is stopped for money. */
    enum CreditStop {
        /** The hold and the renter's available balance can't cover the next lease. */
        CREDIT_EXHAUSTED,
        /** The session has reached its spending cap. */
        MAX_SPEND_REACHED
    }

    /**
     * RUNNING to FINISHING: its agent is told to write a checkpoint and stop within the grace period. Does
     * nothing, returning false, unless the session is running; repeating it changes nothing.
     */
    boolean stopForCredit(UUID sessionId, CreditStop reason);
}
