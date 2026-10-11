package com.idleo.controlplane.identity.internal;

import java.util.Locale;

/** Stored in identity.users.status as lowercase text, limited by a CHECK constraint. */
enum UserStatus {
    /** Signed in but not yet activated with an invite code. */
    WAITING,
    ACTIVE,
    SUSPENDED;

    String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    static UserStatus fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
