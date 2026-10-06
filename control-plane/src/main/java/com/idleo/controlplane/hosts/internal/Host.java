package com.idleo.controlplane.hosts.internal;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** A machine a user has registered to rent out. */
@Table(schema = "hosts", name = "hosts")
record Host(
        @Id UUID id,
        UUID ownerUserId,
        String name,
        String status,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant lastSeenAt,
        @Version @Nullable Long version) {

    static Host newHost(UUID id, UUID ownerUserId, String name, Instant now) {
        return new Host(id, ownerUserId, name, HostStatus.PENDING.dbValue(), now, now, null, null);
    }
}
