package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.SessionLowOnCredit;
import com.idleo.controlplane.sessions.SessionMilestones;
import com.idleo.controlplane.sessions.SessionSettled;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Announces what metering reports about a session's money as events, recorded with its transaction. */
@Service
class SessionMilestoneService implements SessionMilestones {

    private final ApplicationEventPublisher events;

    SessionMilestoneService(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void settled(UUID sessionId) {
        events.publishEvent(new SessionSettled(sessionId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lowOnCredit(UUID sessionId) {
        events.publishEvent(new SessionLowOnCredit(sessionId));
    }
}
