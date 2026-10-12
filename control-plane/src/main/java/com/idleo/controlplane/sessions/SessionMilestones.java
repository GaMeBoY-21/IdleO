package com.idleo.controlplane.sessions;

import java.util.UUID;

/**
 * What metering tells the sessions module about a session's money, so the sessions module can announce it as an
 * event (ADR 0026) without anything depending on metering (ADR 0017). Call it inside the transaction that made
 * the change; the event is recorded with it and delivered after it commits.
 */
public interface SessionMilestones {

    /** The session's usage is closed: its last charge is posted and its hold released. */
    void settled(UUID sessionId);

    /** The session's credit fell under 10 minutes. */
    void lowOnCredit(UUID sessionId);
}
