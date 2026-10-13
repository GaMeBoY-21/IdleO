package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.Rupees;
import com.idleo.controlplane.sessions.SessionOutcomes.Outcome;
import java.util.UUID;
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

    private static String service(Outcome session) {
        return session.serviceName() == null ? SESSION_ENDED_FALLBACK : session.serviceName();
    }

    private static String sessionLink(UUID sessionId) {
        return "/session/?id=" + sessionId;
    }
}
