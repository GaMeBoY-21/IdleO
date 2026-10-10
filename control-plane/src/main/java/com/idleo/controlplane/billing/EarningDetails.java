package com.idleo.controlplane.billing;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the ledger's host entries don't say: which service a settlement or a refund was for, and for how many minutes
 * (ADR 0023). Billing declares this port and the metering module implements it, because metering already depends on
 * billing and sessions, and billing can't depend on either. The amounts always come from the ledger; this only labels
 * them.
 */
public interface EarningDetails {

    /** A ledger transaction's entry for one host. */
    record Key(UUID txnId, UUID hostId) {}

    /**
     * @param serviceName the service the session ran; null if it isn't in the catalog any more
     * @param minutes the minutes the host was paid for, or, for a refund, the minutes given back
     */
    record Detail(@Nullable String serviceName, int minutes) {}

    /** The detail of each of these settlement and refund transactions; one without any isn't in the map. */
    Map<Key, Detail> of(Collection<Key> keys);
}
