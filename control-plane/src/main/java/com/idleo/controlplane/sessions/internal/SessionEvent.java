package com.idleo.controlplane.sessions.internal;

/** What moves a session from one state to the next. */
enum SessionEvent {
    /** The scheduler reserved a host and credit. */
    MATCH,
    /** No host could take the session. */
    NO_CANDIDATE,
    /** The host's agent accepted the offer. */
    OFFER_ACCEPTED,
    /** The host declined or let the offer expire; the session goes back to waiting. */
    OFFER_DECLINED,
    /** Three offers were declined or expired. */
    OFFERS_EXHAUSTED,
    /** The renter, or the system for them, cancelled before the session ran. */
    CANCEL,
    /** The container started. */
    START,
    /** The start failed after retries, or didn't happen in time. */
    START_FAILED,
    /** The job failed, or exited with a non-zero code, while running. */
    JOB_FAILED,
    /** The job succeeded but its outputs' completion marker never appeared in storage. */
    OUTPUTS_MISSING,
    /**
     * The host was lost while provisioning or running: its lease lapsed, its agent fenced or stopped the
     * attempt, or it reconnected without it.
     */
    LEASE_LAPSED,
    /** The job ended, the renter stopped it, credit ran out, or abuse. */
    FINISH,
    /** A batch job or endpoint whose host was lost goes back to the scheduler as its next attempt. */
    REMATCH,
    /** A session lost its host too many times (3 by default) and fails. */
    LOST_REPEATEDLY,
    /** A game whose host was lost ends with a refund. */
    GAME_LOST,
    /** Outputs were uploaded and acknowledged. */
    COMPLETE
}
