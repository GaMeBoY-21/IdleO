package com.idleo.controlplane;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What one module has waiting for an admin (ADR 0053), for the console's one inbox: each kind of work is a bean in the
 * module that owns it, reading its own tables. A decision stays on that kind's own screen; the inbox only finds it,
 * times it and says who's on it.
 */
public interface WorkQueue {

    /** Which kind of work this is. Two beans can share one when two modules hold parts of it. */
    String type();

    /** What an admin must hold to see this kind. */
    AdminPermission permission();

    /** Everything open now. Whatever's gone from here is resolved. */
    List<Item> open();

    /** One item, for after an action, or empty if it's no longer open. */
    default Optional<Item> find(String sourceId) {
        return open().stream().filter(item -> item.sourceId().equals(sourceId)).findFirst();
    }

    /** Whether it can answer the person: only then can a macro have words. */
    default boolean replies() {
        return false;
    }

    /** Answers the person through the module's own path, and leaves it {@code status}. */
    default void reply(String sourceId, String body, String status, UUID admin) {
        throw new UnsupportedOperationException(type() + " can't answer anyone");
    }

    /** Whether its state can be changed from the inbox: closing it, or waiting on the person. */
    default boolean hasStatus() {
        return false;
    }

    /** Sets its state: {@code open}, {@code waiting} or {@code resolved}. Audited by the module. */
    default void status(String sourceId, String status, String reason, UUID admin) {
        throw new UnsupportedOperationException(type() + " has no state to set");
    }

    /**
     * One piece of work.
     *
     * @param sourceId what it's called in the module that owns it
     * @param subjectKind user, host, session or payment: what's on its page, when it has one
     * @param person whose it is, when it's someone's
     * @param title what it is, in words, with no one's personal data
     * @param link the console's address for its own detail screen
     * @param sla which target times it (the Owner's table)
     * @param dueAt a due time that isn't ours to set: Razorpay's evidence deadline; null to use the target
     * @param waiting whether it's waiting on the person
     * @param firstReplyAt when an admin first answered, for what has a first reply
     * @param activityAt when someone last did something to it on its own screen
     * @param moneyPaise what's at stake
     * @param runningSession whether a session is running that this affects
     */
    record Item(
            String sourceId,
            @Nullable String subjectKind,
            @Nullable UUID subjectId,
            @Nullable UUID person,
            String title,
            String link,
            String sla,
            Instant openedAt,
            @Nullable Instant dueAt,
            boolean waiting,
            @Nullable Instant firstReplyAt,
            @Nullable Instant activityAt,
            long moneyPaise,
            boolean runningSession) {}
}
