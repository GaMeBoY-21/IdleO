package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.sessions.AttemptLost;
import com.idleo.controlplane.sessions.SessionCosts;
import com.idleo.controlplane.sessions.SessionLowOnCredit;
import com.idleo.controlplane.sessions.SessionNeverRan;
import com.idleo.controlplane.sessions.SessionOutcomes;
import com.idleo.controlplane.sessions.SessionOutcomes.Outcome;
import com.idleo.controlplane.sessions.SessionSettled;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns what other modules announce into messages (ADR 0026). Each listener is durable and retried by Spring
 * Modulith's event registry, so each is safe to run twice: the message's dedupe key is the event it answers.
 * Nothing here reaches into another module; it asks their public ports.
 */
@Component
class NotificationListeners {

    private static final Logger log = LoggerFactory.getLogger(NotificationListeners.class);
    private static final Set<String> CREDIT_STOPS = Set.of("credit_exhausted", "max_spend_reached");
    private static final String RENTER_STOPPED = "renter_stopped";

    private final SessionOutcomes sessions;
    private final SessionCosts costs;
    private final HostOwners owners;
    private final Messages messages;
    private final Notifier notifier;

    NotificationListeners(
            SessionOutcomes sessions, SessionCosts costs, HostOwners owners, Messages messages, Notifier notifier) {
        this.sessions = sessions;
        this.costs = costs;
        this.owners = owners;
        this.messages = messages;
        this.notifier = notifier;
    }

    /** A session that ran has ended and its last charge is posted: how it went, with what it cost. */
    @ApplicationModuleListener
    void on(SessionSettled event) {
        var session = outcome(event.sessionId());
        if (session == null) {
            return;
        }
        long charged = costs.of(event.sessionId()).chargedPaise();
        var key = "session-ended:" + event.sessionId();
        switch (session.state()) {
            case "COMPLETED" -> {
                if (RENTER_STOPPED.equals(session.stoppedFor())) {
                    return; // They did it themselves.
                }
                var stoppedFor = session.stoppedFor();
                if (stoppedFor != null && CREDIT_STOPS.contains(stoppedFor)) {
                    notifier.tell(
                            session.renterId(),
                            NotificationKind.STOPPED_FOR_CREDIT,
                            key,
                            messages.stopped(session, stoppedFor, charged));
                } else {
                    notifier.tell(
                            session.renterId(),
                            NotificationKind.SESSION_COMPLETED,
                            key,
                            messages.completed(session, charged));
                }
            }
            case "FAILED", "ENDED_REFUNDED" ->
                notifier.tell(
                        session.renterId(), NotificationKind.SESSION_FAILED, key, messages.failed(session, charged));
            default -> log.warn("Session {} settled in state {}; nothing to tell", event.sessionId(), session.state());
        }
    }

    /** A session that never ran: no GPU was free, or it failed before it started. Nothing was charged. */
    @ApplicationModuleListener
    void on(SessionNeverRan event) {
        var session = outcome(event.sessionId());
        if (session == null) {
            return;
        }
        var key = "session-ended:" + event.sessionId();
        if ("NO_HOST_FREE".equals(session.state())) {
            notifier.tell(session.renterId(), NotificationKind.NO_HOST_FREE, key, messages.noHostFree(session));
        } else if ("FAILED".equals(session.state())) {
            notifier.tell(session.renterId(), NotificationKind.SESSION_FAILED, key, messages.failed(session, 0));
        }
    }

    /** At most one message and one email for each session, however often its credit dips under 10 minutes. */
    @ApplicationModuleListener
    void on(SessionLowOnCredit event) {
        var session = outcome(event.sessionId());
        if (session == null || session.startedAt() == null || isFinal(session)) {
            return;
        }
        notifier.tell(
                session.renterId(),
                NotificationKind.LOW_CREDIT,
                "low-credit:" + event.sessionId(),
                messages.lowCredit(session));
    }

    /** An attempt lost its host: the renter is told it is moving, and the host's owner that it went offline. */
    @ApplicationModuleListener
    void on(AttemptLost event) {
        var session = outcome(event.sessionId());
        if (session == null) {
            return;
        }
        var key = event.sessionId() + ":" + event.attempt();
        notifier.tell(session.renterId(), NotificationKind.HOST_LOST, "host-lost:" + key, messages.hostLost(session));
        sessions.hostOfAttempt(event.sessionId(), event.attempt())
                .ifPresent(hostId -> owners.ownerOf(hostId).ifPresent(ownerId -> {
                    var name = owners.ownedBy(ownerId).stream()
                            .filter(host -> host.id().equals(hostId))
                            .map(HostOwners.OwnedHost::name)
                            .findFirst()
                            .orElse("Your machine");
                    notifier.tell(
                            ownerId,
                            NotificationKind.MACHINE_OFFLINE,
                            "machine-offline:" + key,
                            messages.machineOffline(name, hostId));
                }));
    }

    private Outcome outcome(UUID sessionId) {
        var outcome = sessions.of(sessionId).orElse(null);
        if (outcome == null) {
            log.warn("Session {} is gone; nothing to tell", sessionId);
        }
        return outcome;
    }

    private static boolean isFinal(Outcome session) {
        return Set.of("COMPLETED", "FAILED", "CANCELLED", "NO_HOST_FREE", "ENDED_REFUNDED")
                .contains(session.state());
    }
}
