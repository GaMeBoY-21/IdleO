package com.idleo.controlplane.hosts;

import java.time.Instant;

/** Renewal is allowed only in a certificate's last 10 days; this one's window opens later. */
public class RenewalNotOpenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Instant opensAt;

    public RenewalNotOpenException(Instant opensAt) {
        super("Certificate renewal opens at " + opensAt + ".");
        this.opensAt = opensAt;
    }

    public Instant opensAt() {
        return opensAt;
    }
}
