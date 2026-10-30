package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.images.CheckedImages;
import com.idleo.controlplane.sessions.SessionBookings;
import java.sql.Timestamp;
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

    /**
     * A first attempt still REQUESTED this long after it was requested has missed its match: presence couldn't be read
     * through all of SessionMatcher's quick retries (1, 2, 4 and 8 s), or the match never ran. The re-match loop
     * takes it from there.
     */
    static final Duration STRANDED_AFTER = Duration.ofSeconds(20);

    private final SessionStore store;
    private final SessionJobs jobs;
    private final SessionStarts starts;
    private final SessionHolds holds;
    private final JdbcClient jdbc;

    SessionBookingService(
            SessionStore store, SessionJobs jobs, SessionStarts starts, SessionHolds holds, JdbcClient jdbc) {
        this.store = store;
        this.jobs = jobs;
        this.starts = starts;
        this.holds = holds;
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasRun(UUID sessionId) {
        return holds.hasRun(sessionId);
    }

    @Override
    public Optional<BookingRequest> bookingRequest(UUID sessionId) {
        return store.find(sessionId)
                .flatMap(session -> jobs.spec(session)
                        .map(spec -> new BookingRequest(
                                session.id(),
                                session.renterId(),
                                session.state().name(),
                                session.version(),
                                session.attempt(),
                                spec,
                                session.vramNeededMb(),
                                diskNeededMb(session),
                                session.maxPricePaisePerMin(),
                                session.maxSpendPaise(),
                                jobs.image(session)
                                        .map(CheckedImages.Image::cudaNeeded)
                                        .orElse(null))));
    }

    private int diskNeededMb(SessionStore.Session session) {
        var template = jobs.template(session).orElseThrow();
        return SessionDisk.neededMb(
                template,
                jobs.uploads(session).stream()
                                .mapToLong(named -> named.upload().sizeBytes())
                                .sum()
                        + jobs.imageBytes(session));
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
                        select id, attempt, state_changed_at from sessions.sessions
                        where state = 'REQUESTED' and (attempt > 1 or state_changed_at < :stranded)
                        order by state_changed_at
                        limit 100
                        """)
                .param("stranded", Timestamp.from(store.now().minus(STRANDED_AFTER)))
                // A stranded first attempt has waited for the loop since it was stranded, not since it was requested.
                .query((rs, n) -> new AwaitingRematch(
                        rs.getObject("id", UUID.class),
                        rs.getInt("attempt") > 1
                                ? rs.getTimestamp("state_changed_at").toInstant()
                                : rs.getTimestamp("state_changed_at")
                                        .toInstant()
                                        .plus(STRANDED_AFTER)))
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
