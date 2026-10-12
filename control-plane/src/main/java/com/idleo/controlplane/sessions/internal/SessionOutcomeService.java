package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.SessionOutcomes;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** What the notifications module may read about how a session went. */
@Service
class SessionOutcomeService implements SessionOutcomes {

    private final SessionStore store;
    private final TemplateCatalog catalog;
    private final JdbcClient jdbc;

    SessionOutcomeService(SessionStore store, TemplateCatalog catalog, JdbcClient jdbc) {
        this.store = store;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Outcome> of(UUID sessionId) {
        return store.find(sessionId).map(session -> {
            var events = store.events(sessionId);
            var stoppedFor = events.stream()
                    .filter(event -> "FINISHING".equals(event.toState()))
                    .map(SessionStore.Event::reason)
                    .filter(reason -> !"job_succeeded".equals(reason))
                    .findFirst()
                    .orElse(null);
            var last = events.isEmpty() ? null : events.get(events.size() - 1).reason();
            boolean completed = session.state() == SessionState.COMPLETED;
            return new Outcome(
                    session.id(),
                    session.renterId(),
                    catalog.serviceOf(session.templateId())
                            .map(service -> service.name())
                            .orElse(null),
                    session.state().name(),
                    completed ? null : session.errorCode() != null ? session.errorCode() : last,
                    stoppedFor,
                    session.startedAt());
        });
    }

    @Override
    public Optional<UUID> hostOfAttempt(UUID sessionId, int attempt) {
        return jdbc.sql("select host_id from sessions.attempts where session_id = :sessionId and attempt = :attempt")
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .query(UUID.class)
                .optional();
    }
}
