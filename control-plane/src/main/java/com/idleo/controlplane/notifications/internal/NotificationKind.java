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
    SESSION_COMPLETED("session-completed", "Session finished", null),
    SESSION_FAILED("session-failed", "Session failed", null),
    STOPPED_FOR_CREDIT("stopped-for-credit", "Stopped for credit or cap", "Stopped sessions are about your money."),
    LOW_CREDIT("low-credit", "Low credit", null),
    NO_HOST_FREE("no-host-free", "No GPU free", null),
    HOST_LOST("host-lost", "Host lost", "A lost host changes your charges."),
    OUTPUTS_EXPIRING("outputs-expiring", "Outputs expiring soon", null),
    MACHINE_OFFLINE("machine-offline", "Machine offline", "It affects a renter and your earnings."),
    AGENT_UPDATE_NEEDED("agent-update-needed", "Host update needed", null),
    WEEKLY_EARNINGS("weekly-earnings", "Weekly earnings", null),
    PAYOUT("payout", "Payout sent or failed", "Payouts are your money."),
    TOP_UP_CONFIRMED("top-up-confirmed", "Top-up confirmed", "Top-ups are your money."),
    NEW_DEVICE_SIGN_IN("new-device-sign-in", "New device sign-in", "It protects your account.");

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
