package com.idleo.controlplane;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * What the enforcement ladder has limited on an account (ADR 0053), asked by the module each limit is about. The rest of
 * the account goes on working.
 */
public interface AccountLimits {

    /** Whether they may use their own container images. */
    boolean noCustomImages(UUID user);

    /** Whether their payouts are held, for misconduct as a host. */
    boolean payoutsHeld(UUID user);

    /** How many session hours a week they may run, when that's capped. */
    OptionalInt sessionHoursCap(UUID user);
}
