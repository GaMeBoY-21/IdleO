package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A completed session's outputs for its renter (ADR 0016): the files the successful attempt wrote under
 * {@code outputs/}, each with a short-lived pre-signed download link. Pre-signed links are right here: each
 * is a single-object download for the renter, unlike the agent's scoped uploads.
 */
@Component
class SessionOutputs {

    private final SessionService sessions;
    private final SessionStorage storage;
    private final StorageProperties properties;
    private final SessionStore store;

    SessionOutputs(SessionService sessions, SessionStorage storage, StorageProperties properties, SessionStore store) {
        this.sessions = sessions;
        this.storage = storage;
        this.properties = properties;
        this.store = store;
    }

    record Output(String path, long sizeBytes, String url) {}

    record Outputs(UUID sessionId, int attempt, Instant linksExpireAt, List<Output> files) {}

    /** The outputs of the renter's session; 404 if it isn't theirs, 409 unless it's COMPLETED. */
    Outputs of(UUID renterId, UUID sessionId) {
        var session = sessions.owned(renterId, sessionId);
        if (session.state() != SessionState.COMPLETED) {
            throw ApiProblem.of(
                    Problem.OUTPUTS_NOT_READY,
                    "Outputs can be downloaded once the session is COMPLETED; it's " + session.state() + ".");
        }
        var prefix = StorageLayout.outputsPrefix(sessionId, session.attempt());
        var ttl = properties.downloadLinkTtl();
        var expiresAt = store.now().plus(ttl);
        var files = storage.list(prefix).stream()
                .filter(object -> !object.key().equals(prefix + StorageLayout.COMPLETE))
                .map(object -> new Output(
                        object.key().substring(prefix.length()),
                        object.size(),
                        storage.downloadLink(object.key(), ttl).toString()))
                .toList();
        return new Outputs(sessionId, session.attempt(), expiresAt, files);
    }
}
