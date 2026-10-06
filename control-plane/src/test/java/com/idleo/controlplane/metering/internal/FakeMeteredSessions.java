package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.sessions.MeteredSessions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Sessions as metering sees them, set up by a test: attempts, and lease stretches the test chooses. */
final class FakeMeteredSessions implements MeteredSessions {

    record Stretch(int attempt, Instant from, Instant until) {}

    final Map<UUID, Session> sessions = new HashMap<>();
    final Map<UUID, List<Attempt>> attempts = new HashMap<>();
    final Map<UUID, List<Stretch>> leases = new HashMap<>();
    final Map<String, Instant> checkpoints = new HashMap<>();

    void put(Session session, Attempt... attempts) {
        sessions.put(session.sessionId(), session);
        this.attempts.put(session.sessionId(), new ArrayList<>(List.of(attempts)));
    }

    void lease(UUID sessionId, int attempt, Instant from, Instant until) {
        leases.computeIfAbsent(sessionId, id -> new ArrayList<>()).add(new Stretch(attempt, from, until));
    }

    @Override
    public Optional<Session> session(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<Attempt> attempts(UUID sessionId) {
        return attempts.getOrDefault(sessionId, List.of());
    }

    void checkpoint(UUID sessionId, int attempt, Instant completedAt) {
        checkpoints.put(sessionId + ":" + attempt, completedAt);
    }

    @Override
    public Optional<Instant> lastCompleteCheckpointAt(UUID sessionId, int attempt) {
        return Optional.ofNullable(checkpoints.get(sessionId + ":" + attempt));
    }

    @Override
    public java.time.Duration leaseTtl() {
        return java.time.Duration.ofMinutes(2);
    }

    @Override
    public boolean leaseCovered(UUID sessionId, int attempt, Instant from, Instant until) {
        return leases.getOrDefault(sessionId, List.of()).stream()
                .anyMatch(stretch -> stretch.attempt() == attempt
                        && !stretch.from().isAfter(from)
                        && !stretch.until().isBefore(until));
    }
}
