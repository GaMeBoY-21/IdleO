package com.idleo.controlplane.hosts.internal;

import java.util.Locale;

/** Stored in hosts.hosts.status as lowercase text, limited by a CHECK constraint. New hosts are pending; a removed host keeps its row, because the ledger points at it, and never comes back. */
enum HostStatus {
    PENDING,
    VERIFIED,
    ACTIVE,
    SUSPENDED,
    REMOVED;

    String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
