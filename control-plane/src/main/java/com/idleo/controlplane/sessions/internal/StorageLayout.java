package com.idleo.controlplane.sessions.internal;

import java.util.UUID;

/**
 * The bucket layout, exactly as proto/README.md "Storage layout" (ADR 0016, ADR 0019). A session's input is an
 * upload, {@code uploads/{id}/{name}}, which belongs to the uploads, not to the session.
 */
final class StorageLayout {

    static final String COMPLETE = "_COMPLETE";

    private StorageLayout() {}

    static String sessionPrefix(UUID sessionId) {
        return "sessions/" + sessionId + "/";
    }

    static String attemptPrefix(UUID sessionId, int attempt) {
        return sessionPrefix(sessionId) + "attempt-" + attempt + "/";
    }

    static String checkpointsPrefix(UUID sessionId, int attempt) {
        return attemptPrefix(sessionId, attempt) + "checkpoints/";
    }

    /** One checkpoint's folder: its seq as 8 zero-padded digits. */
    static String checkpointFolder(UUID sessionId, int attempt, int seq) {
        return checkpointsPrefix(sessionId, attempt) + String.format("%08d/", seq);
    }

    static String outputsPrefix(UUID sessionId, int attempt) {
        return attemptPrefix(sessionId, attempt) + "outputs/";
    }

    static String logsPrefix(UUID sessionId, int attempt) {
        return attemptPrefix(sessionId, attempt) + "logs/";
    }

    /** One log chunk: its seq as 8 zero-padded digits. */
    static String logChunk(UUID sessionId, int attempt, int seq) {
        return logsPrefix(sessionId, attempt) + String.format("%08d.log", seq);
    }
}
