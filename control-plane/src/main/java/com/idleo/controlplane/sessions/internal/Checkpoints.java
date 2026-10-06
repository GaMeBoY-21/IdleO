package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Complete checkpoints (proto/README.md "Resume", ADR 0016, ADR 0017). A checkpoint counts only once its
 * {@code _COMPLETE} marker is in storage, never on the agent's word, and it counts from when storage finished
 * writing that marker, on storage's clock: that's what a resume starts from, and where a lost host's minutes stop
 * being charged.
 */
@Component
class Checkpoints {

    private static final Logger log = LoggerFactory.getLogger(Checkpoints.class);

    /** A complete checkpoint, and when its marker was written. */
    record Complete(int seq, Instant completedAt) {}

    private final SessionStorage storage;
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final SessionStore store;

    Checkpoints(SessionStorage storage, JdbcClient jdbc, IdGenerator ids, SessionStore store) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.ids = ids;
        this.store = store;
    }

    /**
     * The attempt's newest complete checkpoint, from a listing of storage rather than what the agent reported, so
     * one finished just before its host died still counts. It's recorded if it wasn't yet.
     */
    Optional<Complete> newest(UUID sessionId, int attempt) {
        var prefix = StorageLayout.checkpointsPrefix(sessionId, attempt);
        var newest = storage.list(prefix).stream()
                .filter(object -> object.key().endsWith("/" + StorageLayout.COMPLETE))
                .flatMap(object -> {
                    var seq = object.key()
                            .substring(prefix.length(), object.key().length() - StorageLayout.COMPLETE.length() - 1);
                    return seq.matches("\\d{8}") && Integer.parseInt(seq) >= 1
                            ? java.util.stream.Stream.of(new Complete(Integer.parseInt(seq), object.lastModified()))
                            : java.util.stream.Stream.empty();
                })
                .max(Comparator.comparingInt(Complete::seq));
        newest.ifPresent(complete -> insert(sessionId, attempt, complete));
        return newest;
    }

    /** Records checkpoint {@code seq} if its marker is in storage and nothing as new is recorded yet. */
    void record(UUID sessionId, int attempt, int seq) {
        var known = jdbc.sql("""
                        select exists (select 1 from sessions.checkpoints
                                       where session_id = :id and attempt = :attempt and step >= :seq)
                        """)
                .param("id", sessionId)
                .param("attempt", attempt)
                .param("seq", seq)
                .query(Boolean.class)
                .single();
        if (known) {
            return;
        }
        var completedAt =
                storage.lastModified(StorageLayout.checkpointFolder(sessionId, attempt, seq) + StorageLayout.COMPLETE);
        if (completedAt.isEmpty()) {
            log.warn(
                    "Session {} attempt {} reported checkpoint {}, but its completion marker isn't in storage; not "
                            + "recorded",
                    sessionId,
                    attempt,
                    seq);
            return;
        }
        insert(sessionId, attempt, new Complete(seq, completedAt.get()));
    }

    private void insert(UUID sessionId, int attempt, Complete complete) {
        jdbc.sql("""
                        insert into sessions.checkpoints (id, session_id, attempt, object_key, step, completed_at, created_at)
                        values (:id, :sessionId, :attempt, :key, :step, :completedAt, :now)
                        on conflict (session_id, attempt, step) do nothing
                        """)
                .param("id", ids.newId())
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .param("key", StorageLayout.checkpointFolder(sessionId, attempt, complete.seq()))
                .param("step", complete.seq())
                .param("completedAt", Timestamp.from(complete.completedAt().truncatedTo(ChronoUnit.MICROS)))
                .param("now", Timestamp.from(store.now()))
                .update();
    }
}
