package com.idleo.controlplane.identity.internal;

import java.util.Locale;

/** Stored in identity.users.status as lowercase text, limited by a CHECK constraint. */
enum UserStatus {
    ACTIVE,
    SUSPENDED;

    String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    static UserStatus fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
