package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.agentgateway.SessionLogProgress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A session's logs (ADR 0019). The agent uploads the job's output in numbered chunks to {@code
 * sessions/{id}/attempt-{n}/logs/{seq}.log} and reports the highest seq uploaded, in heartbeats and statuses. The
 * control plane keeps it per attempt and only ever raises it, so it reads only chunks it knows exist and never lists
 * storage. Only the renter reads them; hosts never do.
 */
@Component
class SessionLogs implements SessionLogProgress {

    private final JdbcClient jdbc;
    private final SessionStorage storage;

    private final SessionHints hints;

    SessionLogs(JdbcClient jdbc, SessionStorage storage, SessionHints hints) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.hints = hints;
    }

    @Override
    public void onLogProgress(UUID hostId, UUID sessionId, int attempt, int latestLogSeq) {
        raise(hostId, sessionId, attempt, latestLogSeq);
    }

    /**
     * Raises the attempt's newest chunk to {@code seq}, if the report comes from the host that ran that attempt and
     * it's higher than what's known; true if it moved. Any attempt counts, not only the current one, so a lost host's
     * last chunks still show.
     */
    boolean raise(UUID hostId, UUID sessionId, int attempt, int seq) {
        boolean raised = jdbc.sql("""
                                update sessions.attempts set latest_log_seq = :seq
                                where session_id = :sessionId and attempt = :attempt and host_id = :hostId
                                  and latest_log_seq < :seq
                                """)
                        .param("seq", seq)
                        .param("sessionId", sessionId)
                        .param("attempt", attempt)
                        .param("hostId", hostId)
                        .update()
                == 1;
        if (raised) {
            hints.changed(sessionId);
        }
        return raised;
    }

    /** An attempt's newest uploaded chunk. */
    record Progress(int attempt, int latestSeq) {}

    /** Each attempt's newest uploaded chunk, first attempt first; attempts with no chunks yet are left out. */
    List<Progress> progress(UUID sessionId) {
        return jdbc.sql("""
                        select attempt, latest_log_seq from sessions.attempts
                        where session_id = :sessionId and latest_log_seq > 0
                        order by attempt
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Progress(rs.getInt("attempt"), rs.getInt("latest_log_seq")))
                .list();
    }

    /** One chunk, as text. */
    record Chunk(int attempt, int seq, String text) {}

    /** Up to {@code limit} chunks after ({@code afterAttempt}, {@code afterSeq}), in attempt then seq order. */
    record Page(List<Chunk> chunks, boolean more) {}

    Page page(UUID sessionId, int afterAttempt, int afterSeq, int limit) {
        var chunks = new ArrayList<Chunk>();
        boolean more = false;
        for (var progress : progress(sessionId)) {
            if (progress.attempt() < afterAttempt) {
                continue;
            }
            int from = progress.attempt() == afterAttempt ? afterSeq + 1 : 1;
            for (int seq = from; seq <= progress.latestSeq(); seq++) {
                if (chunks.size() == limit) {
                    more = true;
                    break;
                }
                chunks.add(new Chunk(progress.attempt(), seq, text(sessionId, progress.attempt(), seq)));
            }
            if (more) {
                break;
            }
        }
        return new Page(chunks, more);
    }

    /** A chunk's text: UTF-8, with invalid bytes replaced; empty if storage no longer has it. */
    private String text(UUID sessionId, int attempt, int seq) {
        return storage.read(StorageLayout.logChunk(sessionId, attempt, seq))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElse("");
    }

    /** Where a client continues from: the last chunk returned, or where it already was. */
    static @Nullable String cursorAfter(Page page, @Nullable String given) {
        if (page.chunks().isEmpty()) {
            return given;
        }
        var last = page.chunks().getLast();
        return Cursors.ofChunk(last.attempt(), last.seq());
    }
}
