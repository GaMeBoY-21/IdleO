package com.idleo.controlplane;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What one module holds about a person, and what it does with it when they ask (ADR 0037, docs/reference/personal-data-map.md).
 * Each module that keeps anything about a person declares one bean of this type; the privacy module asks them all, so
 * it never reads another module's tables and nothing depends on it.
 *
 * <ul>
 *   <li>{@link #summary}: counts and dates, for Account, Your data.
 *   <li>{@link #export}: the records themselves, for the download of everything.
 *   <li>{@link #blockers}: what stops the account being deleted now, said plainly with what to do.
 *   <li>{@link #erase}: one step of erasure, idempotent, so a job stopped halfway runs the step again safely.
 * </ul>
 */
public interface PersonalData {

    /** The module, for the erasure's record and logs: {@code identity}, {@code sessions}. */
    String module();

    /**
     * One group of what we hold, for "Your data": {@code group} is one of {@link #GROUPS}, {@code label} what the
     * count counts ("sessions", "files"), {@code earliest} and {@code latest} the dates it spans (null when empty).
     */
    record Group(
            String group,
            String label,
            long count,
            @Nullable Instant earliest,
            @Nullable Instant latest) {}

    /** The groups "Your data" shows, in order; an export's files are named after them. */
    List<String> GROUPS = List.of("account", "sessions", "files", "money", "hosting", "messages", "reports");

    List<Group> summary(UUID userId);

    /**
     * Part of the export: {@code records} goes into {@code <group>.json} under {@code key}, as JSON: lists, maps,
     * records, strings, numbers and times only, money as integer paise beside rupees where a person reads it.
     */
    record Part(String group, String key, Object records) {}

    List<Part> export(UUID userId);

    /**
     * Something that stops the account being deleted now: {@code code} for the app, {@code message} in the person's
     * words with what to do, and an amount when there's money in it.
     */
    record Blocker(String code, String message, @Nullable Long amountPaise) {}

    default List<Blocker> blockers(UUID userId) {
        return List.of();
    }

    /**
     * What the person gives up by deleting the account, said before they confirm: the free credit they haven't used,
     * which is forfeited (a later payment partner may refund paid credit to where it came from instead).
     */
    record Loss(String code, String message, long amountPaise) {}

    default List<Loss> losses(UUID userId) {
        return List.of();
    }

    /**
     * What the erasure waits for once the waiting period is over, said before the person confirms, with the date when
     * there is one: their machines' earnings paid out to their UPI ID first (ADR 0040), the part still in its dispute
     * window once it's available. Unlike a {@link Blocker}, it doesn't stop them asking.
     */
    record Wait(
            String code,
            String message,
            @Nullable Long amountPaise,
            @Nullable Instant until) {}

    default List<Wait> waits(UUID userId) {
        return List.of();
    }

    /**
     * The erasure's steps, in order (ADR 0037). A module does its part of each step it has a part in; the privacy
     * module runs the identity provider, the erased list and the last email itself.
     */
    enum ErasureStep {
        /** Nothing more can be done as them: suspended, keys and device sign-ins revoked, no admin role. */
        LOCK,
        /** Their machines removed as "Remove" does: the agent hears it, the laptop app says so. */
        REMOVE_HOSTS,
        /** Their files, outputs, checkpoints and session logs deleted from storage, and the listing checked empty. */
        DELETE_FILES,
        /** Profile, sign-in identities, choices, inbox, links, exports and tickets' personal fields deleted. */
        DELETE_RECORDS,
        /** Money records and ratings kept by opaque id only; leftover free credit forfeited. */
        DE_IDENTIFY
    }

    default void erase(UUID userId, ErasureStep step) {}

    /**
     * Before a scheduled erasure checks its blockers: start what has to finish first, so a blocker can clear (ADR 0039:
     * bought credit refunded to where it was paid from). Called each time the erasure looks, so it starts things once.
     */
    default void beforeErasure(UUID userId) {}
}
