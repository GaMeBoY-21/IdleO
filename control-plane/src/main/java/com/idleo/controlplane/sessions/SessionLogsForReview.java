package com.idleo.controlplane.sessions;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A session's log, for an admin reviewing a report on it (ADR 0039): the trust module reads it only through a report,
 * and audits each read. The same chunks, in the same order, as the renter's own {@code GET /v1/sessions/{id}/logs}.
 */
public interface SessionLogsForReview {

    record Chunk(int attempt, int seq, String text) {}

    /** {@code nextCursor} is null once there's nothing more now. */
    record Page(List<Chunk> chunks, @Nullable String nextCursor) {}

    /** Up to {@code limit} chunks after {@code cursor} (null: from the start); a cursor not of ours is a 400. */
    Page page(UUID sessionId, @Nullable String cursor, int limit);
}
