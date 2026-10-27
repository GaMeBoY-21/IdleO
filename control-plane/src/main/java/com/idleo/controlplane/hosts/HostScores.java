package com.idleo.controlplane.hosts;

import java.util.UUID;

/**
 * Keeping the host score up to date. The hosts module works the score out
 * nightly, and sessions asks for a host's to be worked out again after one of its sessions ends or loses it. The
 * scheduler reads the result through {@link SchedulableHosts.Candidate}, never from here.
 */
public interface HostScores {

    /** Works out this host's score again from the last 30 days. Safe to call any number of times. */
    void refresh(UUID hostId);
}
