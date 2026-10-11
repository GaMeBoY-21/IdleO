package com.idleo.controlplane.notifications.internal;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Every kind of message a person may be sent, from docs/plan/product-surface.md section 9. Each can be
 * switched for email and for the app, except the kinds about money and safety, which are always on
 * ({@link #lockedReason()}). A kind that nothing sends yet still has its switch, so a choice made today
 * holds when it starts.
 */
enum NotificationKind {
    SESSION_COMPLETED("session-completed", "A session finished, with the charge and a link to the outputs", null),
    SESSION_FAILED("session-failed", "A session failed, and why", null),
    STOPPED_FOR_CREDIT(
            "stopped-for-credit",
            "A session stopped because credit ran out or the spending cap was reached",
            "A stopped session is about your money, so you're always told."),
    LOW_CREDIT("low-credit", "Credit is low: under 10 minutes left on a running session", null),
    NO_HOST_FREE("no-host-free", "No machine was free for your session", null),
    HOST_LOST(
            "host-lost",
            "The machine running your session was lost, and what happened next",
            "A lost machine changes your session and your charges, so you're always told."),
    OUTPUTS_EXPIRING("outputs-expiring", "Your outputs are about to expire", null),
    MACHINE_OFFLINE(
            "machine-offline",
            "Your machine went offline during a session",
            "Your machine going offline affects a renter's work and your earnings, so you're always told."),
    AGENT_UPDATE_NEEDED("agent-update-needed", "Your host software needs an update", null),
    WEEKLY_EARNINGS("weekly-earnings", "Your weekly earnings summary", null),
    PAYOUT("payout", "A payout was sent or failed", "A payout is your money, so you're always told."),
    TOP_UP_CONFIRMED(
            "top-up-confirmed",
            "A top-up was confirmed, with the receipt",
            "A top-up is your money, so you're always told."),
    NEW_DEVICE_SIGN_IN(
            "new-device-sign-in",
            "Someone signed in to your account from a new device",
            "It protects your account, so you're always told.");

    private final String slug;
    private final String title;
    private final @Nullable String lockedReason;

    NotificationKind(String slug, String title, @Nullable String lockedReason) {
        this.slug = slug;
        this.title = title;
        this.lockedReason = lockedReason;
    }

    String slug() {
        return slug;
    }

    String title() {
        return title;
    }

    /** Why this kind can't be switched off, or null when it can. */
    @Nullable
    String lockedReason() {
        return lockedReason;
    }

    boolean locked() {
        return lockedReason != null;
    }

    static Optional<NotificationKind> fromSlug(String slug) {
        return all().stream().filter(kind -> kind.slug.equals(slug)).findFirst();
    }

    static List<NotificationKind> all() {
        return List.of(values());
    }
}
