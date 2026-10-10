package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.HostListings;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A machine a user has registered to rent out. Paused from {@code pausedAt} until {@code pausedUntil}, or until
 * its owner resumes it when that's empty; a pause with an end ends by itself, so it's worked out from the time, never
 * stored as a flag (ADR 0023).
 */
@Table(schema = "hosts", name = "hosts")
record Host(
        @Id UUID id,
        UUID ownerUserId,
        String name,
        String status,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant lastSeenAt,
        @Nullable Instant pausedAt,
        @Nullable Instant pausedUntil,
        int keepFreeGb,
        @Nullable Instant removedAt,
        @Nullable String agentVersion,
        @Version @Nullable Long version) {

    static Host newHost(UUID id, UUID ownerUserId, String name, Instant now) {
        return new Host(
                id,
                ownerUserId,
                name,
                HostStatus.PENDING.dbValue(),
                now,
                now,
                null,
                null,
                null,
                HostListings.Listing.DEFAULT_KEEP_FREE_GB,
                null,
                null,
                null);
    }

    boolean paused(Instant now) {
        return pausedAt != null && (pausedUntil == null || now.isBefore(pausedUntil));
    }

    boolean removed() {
        return HostStatus.REMOVED.dbValue().equals(status);
    }
}
