package com.idleo.controlplane;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * One kind of event for the console's one timeline (ADR 0052), in the module that owns it, reading its own tables: a
 * sign-in, a top-up, a session started, a payout sent, a ticket opened, an admin action. An admin only sees the kinds
 * whose permission they hold, and every event goes out through the same masking as every admin response.
 */
public interface AdminTimeline {

    /**
     * Whose timeline it is.
     *
     * @param kind user, host, session or payment
     */
    record Subject(String kind, UUID id) {

        public boolean is(String wanted) {
            return kind.equals(wanted);
        }
    }

    /** What's asked for: newest first, up to {@code limit}, none later than {@code notAfter} when it's given. */
    record Window(@Nullable Instant notAfter, int limit) {

        /** {@code notAfter} for SQL: the end of time on a first page. */
        public Instant until() {
            return notAfter == null ? Instant.parse("9999-01-01T00:00:00Z") : notAfter;
        }
    }

    /**
     * One event.
     *
     * @param key what makes it one event, among those at the same instant
     * @param page the console's page it links to, or null when it stays where it is
     * @param entityId the id that page takes
     */
    record TimelineEvent(
            String key,
            Instant at,
            String kind,
            String title,
            @Nullable String detail,
            @Nullable String page,
            @Nullable String entityId) {}

    /** The kind's name, which the timeline filters by. */
    String kind();

    /** What an admin must hold to see these. */
    AdminPermission permission();

    List<TimelineEvent> events(Subject subject, Window window);

    static AdminTimeline of(
            String kind, AdminPermission permission, BiFunction<Subject, Window, List<TimelineEvent>> events) {
        return new AdminTimeline() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public AdminPermission permission() {
                return permission;
            }

            @Override
            public List<TimelineEvent> events(Subject subject, Window window) {
                return events.apply(subject, window);
            }
        };
    }
}
