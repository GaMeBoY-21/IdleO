package com.idleo.controlplane.hosts;

import java.util.UUID;

/**
 * What hosts need to know about the work on a machine. The hosts module declares this port and the sessions module
 * implements it, because sessions already depends on hosts and a call the other way would be a module cycle (ADR 0013).
 */
public interface HostWork {

    /** Whether the host has a session that hasn't ended: matched, starting, running or finishing. */
    boolean isBusy(UUID hostId);
}
