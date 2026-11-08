package com.idleo.controlplane;

import java.util.Arrays;
import java.util.Optional;

/**
 * The switches that stop part of the product at once (ADR 0056): kill switches, and maintenance mode. Each is enforced
 * on the server where the thing it stops happens, never only in the app, and read through {@link Flags}. Every flag
 * here is in {@code flags.flags}, and a test fails if one has no enforcement test.
 *
 * <p>A flag is either running (the product as usual) or <b>paused</b>: for a kill switch, what it names is stopped; for
 * {@link #MAINTENANCE}, the app is read-only. Pausing is immediate; running again is approved by another admin when
 * there is one. Nothing a flag pauses stops a session that's already running.
 *
 * @param key its name in the database, the API and the console
 * @param owner who decides about it: the role that answers for what it stops
 * @param description what pausing it does, for the console
 * @param pausedIfUnknown its safe value when the flags can't be read: paused for what could cost money or let something
 *     risky through unseen, running for the rest, so a database blip doesn't close the business
 * @param emailsOwners whether a change also emails the Owners
 * @param paused what a refused request is told, the first half of "Bookings are paused: …"
 */
public enum Flag {
    BOOKINGS_NEW(
            "bookings.new",
            "Operations",
            "Stops new bookings. Sessions already booked still start, and running sessions carry on.",
            false,
            true,
            "Bookings are paused"),
    SESSIONS_START(
            "sessions.start",
            "Operations",
            "Stops starting sessions on hosts: new bookings are refused and booked ones wait, held, until it runs again"
                    + " (or give up after the re-match wait). Running sessions carry on.",
            false,
            true,
            "New sessions aren't starting"),
    PAYOUTS_SEND(
            "payouts.send",
            "Finance",
            "Stops sending payouts to RazorpayX. Payouts already made keep their state; new ones wait as created and"
                    + " are sent once it runs again. RazorpayX's webhooks still land.",
            true,
            true,
            "Payouts are paused"),
    TOPUPS(
            "topups",
            "Finance",
            "Stops starting new top-ups. A payment already made is still credited, from its confirmation or Razorpay's"
                    + " webhook.",
            false,
            true,
            "Top-ups are paused"),
    SIGNUPS(
            "signups",
            "Operations",
            "Stops new accounts: someone signing in for the first time is refused. Everyone else signs in as usual.",
            false,
            false,
            "Sign-ups are paused"),
    HOSTS_LINK(
            "hosts.link",
            "Operations",
            "Stops linking new machines: the app's link requests, adding a host and new enrolment codes. Linked"
                    + " machines carry on.",
            false,
            false,
            "Linking new machines is paused"),
    IMAGES_CUSTOM(
            "images.custom",
            "Trust & Safety",
            "Stops renters' own images: none is submitted, and no session is booked with one. Running sessions carry on.",
            true,
            false,
            "Your own images are paused"),
    MAINTENANCE(
            "maintenance",
            "Operations",
            "Makes the app read-only, with a banner and the expected end: writes get 503 with Retry-After. The console,"
                    + " Razorpay's and RazorpayX's webhooks, agents, running sessions, metering, sign-in and stopping a"
                    + " session keep working.",
            false,
            true,
            "IdleO is down for maintenance");

    private final String key;
    private final String owner;
    private final String description;
    private final boolean pausedIfUnknown;
    private final boolean emailsOwners;
    private final String paused;

    Flag(String key, String owner, String description, boolean pausedIfUnknown, boolean emailsOwners, String paused) {
        this.key = key;
        this.owner = owner;
        this.description = description;
        this.pausedIfUnknown = pausedIfUnknown;
        this.emailsOwners = emailsOwners;
        this.paused = paused;
    }

    public String key() {
        return key;
    }

    public String owner() {
        return owner;
    }

    public String description() {
        return description;
    }

    public boolean pausedIfUnknown() {
        return pausedIfUnknown;
    }

    public boolean emailsOwners() {
        return emailsOwners;
    }

    /** The first half of what a refused request is told, such as "Bookings are paused". */
    public String pausedText() {
        return paused;
    }

    public static Optional<Flag> of(String key) {
        return Arrays.stream(values()).filter(flag -> flag.key.equals(key)).findFirst();
    }
}
