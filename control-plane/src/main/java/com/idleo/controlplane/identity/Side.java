package com.idleo.controlplane.identity;

import java.util.Locale;

/**
 * The two sides of an IdleO account (ADR 0058): renting GPUs, on the renter app, and hosting a laptop, on the host site.
 * One account has both, each unlocked by an invite code while access is by invitation (ADR 0024); renter credit and host
 * earnings stay apart, as the ledger has them.
 */
public enum Side {
    RENTING,
    HOSTING;

    /** As the database and the API write it: {@code renting}, {@code hosting}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
