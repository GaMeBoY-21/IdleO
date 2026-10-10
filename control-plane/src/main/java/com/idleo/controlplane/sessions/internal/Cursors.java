package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque page cursors (technical design "Limits"): a position in a list, base64url-encoded, so clients pass back
 * what they were given and never build one. A cursor that doesn't decode is a {@code validation-failed} error on
 * {@code cursor}.
 */
final class Cursors {

    private Cursors() {}

    /** After the row with this id, in a list ordered by id (UUIDv7, so by time). */
    static String ofId(UUID id) {
        return encode("i:" + id);
    }

    static UUID id(String cursor) {
        var raw = decode(cursor);
        try {
            if (raw.startsWith("i:")) {
                return UUID.fromString(raw.substring(2));
            }
        } catch (IllegalArgumentException e) {
            // Refused below.
        }
        throw invalid();
    }

    /** After a log chunk: its attempt and seq. */
    static String ofChunk(int attempt, int seq) {
        return encode("c:" + attempt + ":" + seq);
    }

    /** {attempt, seq} of the chunk a cursor points after. */
    static int[] chunk(String cursor) {
        var parts = decode(cursor).split(":");
        try {
            if (parts.length == 3 && parts[0].equals("c")) {
                int attempt = Integer.parseInt(parts[1]);
                int seq = Integer.parseInt(parts[2]);
                if (attempt >= 1 && seq >= 0) {
                    return new int[] {attempt, seq};
                }
            }
        } catch (NumberFormatException e) {
            // Refused below.
        }
        throw invalid();
    }

    /** After one of a host's attempts, in a list newest first: when it started running, and which attempt. */
    static String ofHostAttempt(java.time.Instant startedAt, UUID sessionId, int attempt) {
        return encode("h:" + startedAt.getEpochSecond() + "." + startedAt.getNano() + ":" + sessionId + ":" + attempt);
    }

    /** What a host-attempt cursor points after. */
    record HostAttemptPosition(java.time.Instant startedAt, UUID sessionId, int attempt) {}

    static HostAttemptPosition hostAttempt(String cursor) {
        var parts = decode(cursor).split(":");
        try {
            if (parts.length == 4 && parts[0].equals("h")) {
                var time = parts[1].split("\\.");
                return new HostAttemptPosition(
                        java.time.Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1])),
                        UUID.fromString(parts[2]),
                        Integer.parseInt(parts[3]));
            }
        } catch (RuntimeException e) {
            // Refused below.
        }
        throw invalid();
    }

    /** A page size from 1 to {@code max}; {@code validation-failed} on {@code limit} otherwise. */
    static int limit(int limit, int max) {
        if (limit < 1 || limit > max) {
            throw ApiProblem.invalid("limit", "must be from 1 to " + max + ".");
        }
        return limit;
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static ApiProblem invalid() {
        return ApiProblem.invalid("cursor", "isn't a cursor this API gave out.");
    }
}
