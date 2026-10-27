package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's messages: the in-app list, newest first, and marking them read. */
@RestController
@RequestMapping("/v1/notifications")
class InboxController {

    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 50;

    private final NotificationStore store;

    InboxController(NotificationStore store) {
        this.store = store;
    }

    /**
     * One message. {@code link} is a path in the web app. {@code read} is whether the person has opened or
     * marked it.
     */
    record InboxItem(UUID id, String kind, String title, String body, String link, Instant createdAt, boolean read) {}

    /**
     * @param nextCursor pass it back as {@code cursor} for the next page; null on the last one
     * @param unread how many messages in all haven't been read
     */
    record InboxPage(List<InboxItem> items, @Nullable String nextCursor, int unread) {}

    /**
     * Which messages to mark read, one of two: {@code id}, just that message (opening it); or {@code upTo}, every one
     * up to and including it (the newest, for "Mark all read").
     */
    record ReadRequest(@Nullable UUID id, @Nullable UUID upTo) {}

    record Unread(int unread) {}

    /** The person's messages, newest first, a page at a time. */
    @GetMapping
    InboxPage list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiProblem.invalid("limit", "must be from 1 to " + MAX_PAGE + ".");
        }
        var rows = store.page(user.id(), cursor == null ? null : after(cursor), limit + 1);
        var page = rows.subList(0, Math.min(limit, rows.size()));
        return new InboxPage(
                page.stream().map(InboxController::item).toList(),
                rows.size() > limit ? cursorAfter(page.getLast().id()) : null,
                store.unread(user.id()));
    }

    /**
     * Marks messages read: the one {@code id} names, which is what opening a message does, or every one of the
     * person's up to and including {@code upTo}, the newest for "mark all read". Exactly one of the two. Messages
     * already read keep the time they were read.
     */
    @PostMapping("/read")
    Unread read(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody ReadRequest request) {
        var id = request.id();
        var upTo = request.upTo();
        if ((id == null) == (upTo == null)) {
            throw ApiProblem.invalid("id", "give id, for one message, or upTo, for every one up to it; not both.");
        }
        if (id != null) {
            store.markOneRead(user.id(), id);
        } else {
            store.markRead(user.id(), upTo);
        }
        return new Unread(store.unread(user.id()));
    }

    private static InboxItem item(NotificationStore.Stored stored) {
        return new InboxItem(
                stored.id(),
                stored.kind(),
                stored.message().title(),
                stored.message().body(),
                stored.message().link(),
                stored.createdAt(),
                stored.readAt() != null);
    }

    /** Opaque, like every cursor: clients pass back what they were given. */
    private static String cursorAfter(UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("i:" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID after(String cursor) {
        try {
            var raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (raw.startsWith("i:")) {
                return UUID.fromString(raw.substring(2));
            }
        } catch (IllegalArgumentException e) {
            // Refused below.
        }
        throw ApiProblem.invalid("cursor", "isn't a cursor this API gave out.");
    }
}
