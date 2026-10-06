package com.idleo.controlplane.billing.internal;

import java.util.List;
import java.util.UUID;

/**
 * One balanced ledger transaction. {@code txnId} is derived from what caused it, so posting the same
 * cause again changes nothing (ADR 0014); {@code refType} and {@code refId} name that cause.
 */
record LedgerTransaction(UUID txnId, String refType, UUID refId, List<LedgerEntry> entries) {

    LedgerTransaction {
        entries = List.copyOf(entries);
    }
}
