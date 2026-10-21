package com.idleo.controlplane.identity;

import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The security log (ADR 0037): what happened to an account's access, with the address it came from. It is the only
 * place an IP address is kept, read only in the admin console, and deleted after {@code
 * legal.retention.security-events}. A module records through this port; the address is the current request's, when
 * there is one. Recorded in the caller's transaction when there is one.
 */
public interface SecurityLog {

    enum Kind {
        /** A new sign-in: an ID token with a newer {@code auth_time} than any before it. */
        SIGN_IN,
        /** A token or key that didn't verify; {@code detail} says how ({@code expired}, {@code invalid}). */
        SIGN_IN_FAILED,
        /** A valid token for an account that may not come in ({@code suspended}, {@code email_not_verified}). */
        SIGN_IN_REFUSED,
        API_KEY_CREATED,
        API_KEY_REVOKED,
        CLI_SIGN_IN_APPROVED,
        ADMIN_SESSION_STARTED,
        ADMIN_CODE_FAILED,
        /** The sign-in provider gave the account a new verified email. */
        EMAIL_CHANGED,
        EXPORT_REQUESTED,
        DELETION_REQUESTED,
        DELETION_CONFIRMED,
        DELETION_CANCELLED;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Records {@code kind} for {@code userId} (null when no account can be tied to it); {@code detail} a short code. */
    void record(Kind kind, @Nullable UUID userId, @Nullable String detail);

    default void record(Kind kind, UUID userId) {
        record(kind, userId, null);
    }
}
