package com.idleo.controlplane.identity;

import java.util.UUID;

/**
 * Holding an account, for a module that finds a reason to (ADR 0038): the trust module holds a renter whose session was
 * stopped for software no renter may run on a host's GPU, bans them when an admin confirms it, and lifts it when an admin
 * clears the review item. A held account (suspended or banned) is refused every call but a few of its own
 * ({@link OpenToHeldAccounts}); the person sees the same plain message either way. Each change is one audit row, naming the
 * actor and the reason, in the caller's transaction. A ban is lifted only through here, never by Reinstate on the Users page.
 */
public interface AccountHolds {

    /**
     * Suspends an active account.
     *
     * @return whether the account changed: false when it was held already
     * @throws IllegalStateException when there is no such account, or it is waiting or erased
     */
    boolean hold(UUID userId, String reason, AuditTrail.Actor actor);

    /**
     * Bans an active or suspended account.
     *
     * @return whether the account changed: false when it was banned already
     * @throws IllegalStateException when there is no such account, or it is waiting or erased
     */
    boolean ban(UUID userId, String reason, AuditTrail.Actor actor);

    /**
     * Makes a suspended or banned account active again.
     *
     * @return whether the account changed: false when it was not held
     * @throws IllegalStateException when there is no such account, or it is waiting or erased
     */
    boolean lift(UUID userId, String reason, AuditTrail.Actor actor);
}
