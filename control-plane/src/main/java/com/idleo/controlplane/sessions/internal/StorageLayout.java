package com.idleo.controlplane.sessions.internal;

import java.util.UUID;

/** The bucket layout, exactly as proto/README.md "Storage layout" (ADR 0016). */
final class StorageLayout {

    static final String COMPLETE = "_COMPLETE";

    private StorageLayout() {}

    static String sessionPrefix(UUID sessionId) {
        return "sessions/" + sessionId + "/";
    }

    static String inputPrefix(UUID sessionId) {
        return sessionPrefix(sessionId) + "input/";
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
}
