package com.idleo.controlplane.hosts.internal;

import java.util.Locale;

/** Stored in hosts.hosts.status as lowercase text, limited by a CHECK constraint. New hosts are pending. */
enum HostStatus {
    PENDING,
    VERIFIED,
    ACTIVE,
    SUSPENDED;

    String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
