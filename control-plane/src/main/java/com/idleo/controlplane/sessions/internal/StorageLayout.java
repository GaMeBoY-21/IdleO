package com.idleo.controlplane.sessions.internal;

import java.util.UUID;

/**
 * The bucket layout, exactly as proto/README.md "Storage layout" (ADR 0016, ADR 0019, ADR 0022). A session's input is
 * an upload, its files at {@code uploads/{id}/files/{path}}, which belongs to the uploads, not to the session.
 */
final class StorageLayout {

    static final String COMPLETE = "_COMPLETE";

    private StorageLayout() {}

    /** An upload's folder, which a session's read grant covers. */
    static String uploadPrefix(UUID uploadId) {
        return "uploads/" + uploadId + "/";
    }

    /** One file of an upload, by its stored path. */
    static String uploadFile(UUID uploadId, String storedPath) {
        return uploadPrefix(uploadId) + "files/" + storedPath;
    }

    /** A host's speed-check folder (ADR 0034): one object, overwritten by each self-test. */
    static String speedTestPrefix(UUID hostId) {
        return "speedtest/" + hostId + "/";
    }

    /** The speed check's one object. */
    static String speedTestKey(UUID hostId) {
        return speedTestPrefix(hostId) + "probe";
    }

    /** A person's data export (ADR 0037): one zip, deleted after seven days. */
    static String exportPrefix(UUID exportId) {
        return "exports/" + exportId + "/";
    }

    /** The erased list's folder (ADR 0037): one object a person, named by their opaque id. */
    static final String ERASURES = "erasures/";

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
