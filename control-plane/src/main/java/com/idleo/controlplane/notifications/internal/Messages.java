package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.Rupees;
import com.idleo.controlplane.sessions.SessionOutcomes.Outcome;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The words of each kind of message, in the voice of web/DESIGN.md: what happened, then what to do. Money is
 * {@link Rupees}, the same figures the screens show.
 */
@Component
class Messages {

    private static final String SESSION_ENDED_FALLBACK = "Your session";

    private final EndingWords endings;

    Messages(EndingWords endings) {
        this.endings = endings;
    }

    Message completed(Outcome session, long chargedPaise) {
        return new Message(
                service(session) + " finished",
                "It cost " + Rupees.format(chargedPaise) + ". Your outputs are ready to download.",
                sessionLink(session.sessionId()) + "#outputs");
    }

    Message failed(Outcome session, long chargedPaise) {
        var why = endings.of(session.endCode() == null ? "job_failed" : session.endCode());
        var charge = chargedPaise == 0 ? "You weren't charged." : "It cost " + Rupees.format(chargedPaise) + ".";
        return new Message(
                service(session) + " didn't finish",
                why.sentence() + " " + charge + " " + why.next(),
                sessionLink(session.sessionId()));
    }

    /** Stopped by an admin (ADR 0027): it didn't finish, and it cost only the minutes it ran. */
    Message stoppedByUs(Outcome session, long chargedPaise) {
        var why = endings.of("operator_stopped");
        var charge = chargedPaise == 0 ? "You weren't charged." : "It cost " + Rupees.format(chargedPaise) + ".";
        return new Message(
                service(session) + " didn't finish",
                why.sentence() + " " + charge + " " + why.next(),
                sessionLink(session.sessionId()));
    }

    /** Stopped by the credit running out or by the renter's spending cap; {@code reason} says which. */
    Message stopped(Outcome session, String reason, long chargedPaise) {
        var why = endings.of(reason);
        var title = reason.equals("max_spend_reached")
                ? "Stopped: it reached your spending cap"
                : "Stopped: your credit ran out";
        return new Message(
                title,
                service(session) + ": " + why.sentence() + " It cost " + Rupees.format(chargedPaise) + ". "
                        + why.next(),
                sessionLink(session.sessionId()));
    }

    /** An admin answered the renter's report: what was given back, if anything, and the admin's words. */
    Message reportAnswered(Outcome session, @Nullable Long refundPaise, String reason) {
        var which = session.serviceName() == null
                ? "your report on a session"
                : "your report on a " + session.serviceName() + " session";
        var outcome = refundPaise == null
                ? "We looked into " + which + " and didn't give anything back."
                : "We've given " + Rupees.format(refundPaise) + " back to your credit after " + which
                        + ". It doesn't expire.";
        return new Message("Your report was answered", outcome + " " + reason, "/account/reports/");
    }

    Message lowCredit(Outcome session) {
        return new Message(
                "Your credit is running low",
                service(session) + " has under 10 minutes of credit left. When it runs out it saves a checkpoint"
                        + " and stops, and you can carry on from there.",
                sessionLink(session.sessionId()));
    }

    Message noHostFree(Outcome session) {
        var why = endings.of(session.endCode() == null ? "no_candidate" : session.endCode());
        return new Message(
                "No GPU was free",
                why.sentence() + " You weren't charged. " + why.next(),
                sessionLink(session.sessionId()));
    }

    Message hostLost(Outcome session) {
        return new Message(
                "Your session is moving to another GPU",
                service(session) + "'s host dropped. We're finding another GPU, and it carries on from its last"
                        + " checkpoint if it saved one. Minutes lost with the host aren't charged.",
                sessionLink(session.sessionId()));
    }

    /** To a machine's owner, who never sees the renter or what they ran. */
    Message machineOffline(String machineName, UUID hostId) {
        return new Message(
                machineName + " went offline during a session",
                "It was running a session when it lost its connection, so the session moved to another machine."
                        + " Check that it's on and connected; it takes new work again once it is.",
                "/hosting/machine/?id=" + hostId);
    }

    /** A payout reached the host's UPI ID (ADR 0040). */
    Message payoutProcessed(long netPaise, long tdsPaise) {
        var tax = tdsPaise == 0
                ? ""
                : " " + Rupees.format(tdsPaise) + " was deducted as tax at source and is paid to the government in your"
                        + " PAN's name.";
        return new Message(
                Rupees.format(netPaise) + " is on its way to your UPI ID",
                "This week's payout left us for your UPI ID. Most banks show it within minutes." + tax,
                EARNINGS);
    }

    /** A payout came back: it never left, or the bank sent it back (ADR 0040). {@code why} is the plain words. */
    Message payoutReturned(long netPaise, boolean reversed, String why) {
        return new Message(
                reversed
                        ? "Your bank sent back a payout of " + Rupees.format(netPaise)
                        : "A payout of " + Rupees.format(netPaise) + " didn't go through",
                why,
                reversed ? PAYOUT_DETAILS : EARNINGS);
    }

    /** The UPI ID payouts go to changed: the next payout waits until {@code holdUntil} (ADR 0040). */
    Message payoutMethodChanged(java.time.Instant holdUntil) {
        return new Message(
                "Your payout UPI ID was changed",
                "Your payout UPI ID was changed. Not you? Contact us. No payout goes to the new UPI ID before "
                        + HOLD_DAY.format(holdUntil) + ".",
                PAYOUT_DETAILS);
    }

    /** Earnings already paid out were refunded to a renter after a report (ADR 0040); never names the renter. */
    Message clawedBack(long amountPaise, long owedPaise, UUID sessionId, java.time.Instant at) {
        var how = (owedPaise == 0
                        ? "It was taken from your earnings that haven't been paid yet."
                        : Rupees.format(amountPaise - owedPaise)
                                + " was taken from your earnings that haven't been paid"
                                + " yet, and " + Rupees.format(owedPaise) + " comes out of what you earn next, before"
                                + " your next payout.")
                + " Nothing is taken from your bank account.";
        return new Message(
                "Recovered: refund for session " + sessionId.toString().substring(0, 8) + " on "
                        + RECOVERED_DAY.format(at),
                "A renter's report about this session was decided in their favour after its earnings were paid out, so"
                        + " your share of " + Rupees.format(amountPaise) + " was refunded to them. " + how,
                EARNINGS);
    }

    private static final String EARNINGS = "/hosting/earnings/";
    private static final String PAYOUT_DETAILS = "/hosting/payout-details/";
    private static final java.time.format.DateTimeFormatter HOLD_DAY = java.time.format.DateTimeFormatter.ofPattern(
                    "d MMMM, h:mm a", java.util.Locale.ENGLISH)
            .withZone(java.time.ZoneId.of("Asia/Kolkata"));
    private static final java.time.format.DateTimeFormatter RECOVERED_DAY =
            java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)
                    .withZone(java.time.ZoneId.of("Asia/Kolkata"));

    private static String service(Outcome session) {
        return session.serviceName() == null ? SESSION_ENDED_FALLBACK : session.serviceName();
    }

    private static String sessionLink(UUID sessionId) {
        return "/session/?id=" + sessionId;
    }

    /** A renter's own image blocked after its check (ADR 0042): what, why, and that nothing not started was charged. */
    Message imageBlocked(String reference, String reason) {
        var why = "admin".equals(reason)
                ? "We've blocked it. If you think it's a mistake, write to us from Help."
                : "A newer check found software in it that isn't allowed on IdleO (the acceptable use policy).";
        return new Message(
                "Your image was blocked",
                reference + " can't run on IdleO any more. " + why
                        + " Sessions of it that hadn't started were cancelled, and you weren't charged for them.",
                "/account/images/");
    }
}
