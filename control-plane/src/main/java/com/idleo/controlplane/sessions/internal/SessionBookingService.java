package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.SessionBookings;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The scheduler's view of sessions (ADR 0015): every change a state machine transition. */
@Service
class SessionBookingService implements SessionBookings {

    private final SessionStore store;
    private final TemplateCatalog catalog;
    private final SessionStarts starts;
    private final JdbcClient jdbc;

    SessionBookingService(SessionStore store, TemplateCatalog catalog, SessionStarts starts, JdbcClient jdbc) {
        this.store = store;
        this.catalog = catalog;
        this.starts = starts;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BookingRequest> bookingRequest(UUID sessionId) {
        return store.find(sessionId)
                .flatMap(session -> catalog.find(session.templateId())
                        .map(template -> new BookingRequest(
                                session.id(),
                                session.renterId(),
                                session.state().name(),
                                session.version(),
                                session.attempt(),
                                SessionSpecs.batch(
                                        template,
                                        session.vramNeededMb(),
                                        Duration.ofSeconds(session.maxRuntimeSeconds())),
                                session.vramNeededMb(),
                                session.maxPricePaisePerMin(),
                                session.maxSpendPaise())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean match(UUID sessionId, int expectedVersion, UUID hostId, int pricePaisePerMin) {
        return store.transition(
                        sessionId,
                        SessionEvent.MATCH,
                        "matched",
                        expectedVersion,
                        new SessionStore.HostChange.Assign(hostId, pricePaisePerMin))
                == SessionStore.Outcome.APPLIED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean offerAccepted(UUID sessionId, String gpuUuid) {
        if (store.transition(sessionId, SessionEvent.OFFER_ACCEPTED, "offer_accepted", null, SessionStore.KEEP)
                != SessionStore.Outcome.APPLIED) {
            return false;
        }
        starts.queue(store.find(sessionId).orElseThrow(), gpuUuid);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean offerDeclined(UUID sessionId, String reason) {
        return store.transition(sessionId, SessionEvent.OFFER_DECLINED, reason, null, SessionStore.CLEAR)
                == SessionStore.Outcome.APPLIED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean noHostFree(UUID sessionId, String reason) {
        var state = store.find(sessionId).map(SessionStore.Session::state).orElse(null);
        var event = state == SessionState.MATCHED ? SessionEvent.OFFERS_EXHAUSTED : SessionEvent.NO_CANDIDATE;
        return store.transition(sessionId, event, reason, null, SessionStore.KEEP) == SessionStore.Outcome.APPLIED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(UUID sessionId, String reason) {
        return store.transition(sessionId, SessionEvent.CANCEL, reason, null, SessionStore.KEEP)
                == SessionStore.Outcome.APPLIED;
    }

    @Override
    public List<AwaitingRematch> awaitingRematch() {
        return jdbc.sql("""
                        select id, state_changed_at from sessions.sessions
                        where state = 'REQUESTED' and attempt > 1
                        order by state_changed_at
                        limit 100
                        """)
                .query((rs, n) -> new AwaitingRematch(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("state_changed_at").toInstant()))
                .list();
    }

    @Override
    public Set<UUID> hostsWithLiveSessions(Collection<UUID> hostIds) {
        if (hostIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(
                jdbc.sql("""
                        select host_id from sessions.sessions
                        where host_id in (:hostIds) and state in ('MATCHED', 'PROVISIONING', 'RUNNING', 'FINISHING')
                        """).param("hostIds", hostIds).query(UUID.class).list());
    }
}
