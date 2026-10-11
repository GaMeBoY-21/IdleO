package com.idleo.controlplane.identity.internal;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/** A user: our own account, whichever provider they sign in with. */
@Table(schema = "identity", name = "users")
record User(
        @Id UUID id,
        String status,
        @Nullable String email,
        boolean emailVerified,
        @Nullable String displayName,
        Instant createdAt,
        Instant updatedAt,
        @Version @Nullable Long version) {

    static User newUser(UUID id, UserStatus status, @Nullable String email, @Nullable String displayName, Instant now) {
        return new User(id, status.dbValue(), email, true, displayName, now, now, null);
    }

    UserStatus userStatus() {
        return UserStatus.fromDb(status);
    }

    User withDisplayName(String newDisplayName, Instant now) {
        return new User(id, status, email, emailVerified, newDisplayName, createdAt, now, version);
    }
}
