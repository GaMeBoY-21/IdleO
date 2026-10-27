package com.idleo.controlplane.identity;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The append-only audit log of every privileged action (ADR 0027): admin actions,
 * operator commands, and certificate issues and revocations. Each module records its action through this port in
 * the same database transaction as the action itself, so an action and its row are committed or rolled back
 * together. Nothing can change or delete a row once it's written.
 */
public interface AuditTrail {

    /**
     * Who did it: an admin in the console, an operator running a command, a host's owner, or the system itself (an
     * agent's enrolment, say). {@code userId} is set for an admin and an owner; {@code name} is how the log names
     * them, never by an email or a person's name (ADR 0037): an admin or an owner by their user id, an operator by
     * the handle in {@code OPERATOR}, or what the system was doing.
     */
    record Actor(Kind kind, @Nullable UUID userId, String name) {

        public enum Kind {
            ADMIN,
            OPERATOR,
            OWNER,
            SYSTEM
        }

        public Actor {
            Objects.requireNonNull(kind);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("an actor has a name");
            }
            if ((kind == Kind.ADMIN || kind == Kind.OWNER) != (userId != null)) {
                throw new IllegalArgumentException("an admin or an owner is a user; an operator or the system isn't");
            }
            name = name.strip();
        }

        /** An operator running a command, named by {@code OPERATOR}. */
        public static Actor operator(String name) {
            return new Actor(Kind.OPERATOR, null, name);
        }

        /** A host's owner, acting on their own machine. */
        public static Actor owner(UUID userId) {
            return new Actor(Kind.OWNER, userId, "owner " + userId);
        }

        /** The control plane itself, doing {@code what}. */
        public static Actor system(String what) {
            return new Actor(Kind.SYSTEM, null, what);
        }

        /**
         * How the action's own records name who did it ({@code granted_by}, {@code changed_by}): an operator by their
         * handle alone, as before the console existed; an admin as {@code admin <user id>}.
         */
        public String label() {
            return kind == Kind.ADMIN ? "admin " + name : name;
        }
    }

    /**
     * One action. {@code action} is the module's noun and the verb, such as {@code host.activate}; the target is its
     * type ({@code host}) and id; {@code before} and {@code after} are what changed, as plain records or maps, stored
     * as JSON, and never a secret.
     */
    record Entry(
            Actor actor,
            String action,
            String targetType,
            String targetId,
            String reason,
            @Nullable Object before,
            @Nullable Object after) {}

    /** The signed-in admin, as the log names them. */
    Actor admin(CurrentUser user);

    /**
     * Records the action in the caller's database transaction; called outside one, it throws.
     *
     * @throws IllegalArgumentException when the reason is blank
     */
    void record(Entry entry);
}
