package com.idleo.controlplane.identity;

import java.util.UUID;

/**
 * What the identity module says about an account that another module acts on (ADR 0041). The payouts module asks it before it
 * pays: an account suspended for misconduct as a host has its payouts held until the suspension is lifted, while one
 * suspended for any other reason is paid as before.
 */
public interface AccountStandings {

    /** Whether this account is suspended with the cause {@code host_misconduct}, which holds its payouts. */
    boolean payoutsHeld(UUID userId);
}
