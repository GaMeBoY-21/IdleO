package com.idleo.controlplane.sessions.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Upload rows (ADR 0019, ADR 0022), {@code sessions.uploads}; their files are {@link UploadFileStore}'s. */
@Component
class UploadStore {

    /** The final session states, for "a session that hasn't ended". */
    static final String FINAL_STATES = Arrays.stream(SessionState.values())
            .filter(state -> state.isFinal)
            .map(state -> "'" + state.name() + "'")
            .collect(Collectors.joining(", "));

    enum Status {
        PENDING,
        READY,
        FAILED,
        EXPIRED,
        DELETED
    }

    /**
     * An upload: one or more files.
     *
     * @param name as the renter named it: the folder's or the file's name
     * @param sizeBytes all its files together
     * @param partSizeBytes every part's size but each file's last, which may be smaller
     * @param lastUsedAt when a session last took it as an input; null if none has
     */
    record Upload(
            UUID id,
            UUID userId,
            String name,
            long sizeBytes,
            int fileCount,
            long partSizeBytes,
            Status status,
            String idempotencyKey,
            String requestHash,
            Instant createdAt,
            @Nullable Instant completedAt,
            @Nullable Instant lastUsedAt,
            Instant expiresAt,
            int version) {

        /** Its folder in storage, which a session's read grant covers. */
        String prefix() {
            return StorageLayout.uploadPrefix(id);
        }
    }

    private final JdbcClient jdbc;

    UploadStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new upload; false if the user already used its idempotency key. */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean insert(Upload upload) {
        return jdbc.sql("""
                                insert into sessions.uploads
                                    (id, user_id, name, size_bytes, file_count, part_size_bytes, status,
                                     idempotency_key, request_hash, created_at, completed_at, expires_at, version)
                                values (:id, :userId, :name, :size, :fileCount, :partSize, :status,
                                        :idempotencyKey, :hash, :createdAt, null, :expiresAt, 0)
                                on conflict (user_id, idempotency_key) do nothing
                                """)
                        .param("id", upload.id())
                        .param("userId", upload.userId())
                        .param("name", upload.name())
                        .param("size", upload.sizeBytes())
                        .param("fileCount", upload.fileCount())
                        .param("partSize", upload.partSizeBytes())
                        .param("status", upload.status().name().toLowerCase(java.util.Locale.ROOT))
                        .param("idempotencyKey", upload.idempotencyKey())
                        .param("hash", upload.requestHash())
                        .param("createdAt", Timestamp.from(upload.createdAt()))
                        .param("expiresAt", Timestamp.from(upload.expiresAt()))
                        .update()
                == 1;
    }

    /** Moves an upload to {@code status}, with when it completed and when it now expires. */
    @Transactional(propagation = Propagation.MANDATORY)
    void finish(UUID id, Status status, @Nullable Instant completedAt, Instant expiresAt) {
        jdbc.sql("""
                        update sessions.uploads
                        set status = :status, completed_at = :completedAt, expires_at = :expiresAt,
                            version = version + 1
                        where id = :id
                        """)
                .param("status", status.name().toLowerCase(java.util.Locale.ROOT))
                .param("completedAt", completedAt == null ? null : Timestamp.from(completedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("id", id)
                .update();
    }

    /**
     * A session took these uploads as inputs at {@code now}: each ready one expires {@code ttl} after that, if that's
     * later than it would have.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void used(java.util.Collection<UUID> ids, Instant now, java.time.Duration ttl) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        update sessions.uploads
                        set last_used_at = :now, expires_at = greatest(expires_at, :expiresAt), version = version + 1
                        where id in (:ids) and status = 'ready'
                        """)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(now.plus(ttl)))
                .param("ids", ids)
                .update();
    }

    Optional<Upload> find(UUID id) {
        return jdbc.sql("select * from sessions.uploads where id = :id")
                .param("id", id)
                .query(UploadStore::upload)
                .optional();
    }

    /** The upload, locked for the rest of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Upload> lock(UUID id) {
        return jdbc.sql("select * from sessions.uploads where id = :id for update")
                .param("id", id)
                .query(UploadStore::upload)
                .optional();
    }

    Optional<Upload> findByKey(UUID userId, String idempotencyKey) {
        return jdbc.sql("select * from sessions.uploads where user_id = :userId and idempotency_key = :key")
                .param("userId", userId)
                .param("key", idempotencyKey)
                .query(UploadStore::upload)
                .optional();
    }

    /** The user's uploads, newest first, after {@code after} if given; at most {@code limit}. */
    List<Upload> page(UUID userId, @Nullable UUID after, int limit) {
        return jdbc.sql("""
                        select * from sessions.uploads
                        where user_id = :userId and (cast(:after as uuid) is null or id < cast(:after as uuid))
                        order by id desc limit :limit
                        """)
                .param("userId", userId)
                .param("after", after)
                .param("limit", limit)
                .query(UploadStore::upload)
                .list();
    }

    /** What the user holds in pending and ready uploads, in bytes. */
    long held(UUID userId) {
        return jdbc.sql("""
                        select coalesce(sum(size_bytes), 0) from sessions.uploads
                        where user_id = :userId and status in ('pending', 'ready')
                        """).param("userId", userId).query(Long.class).single();
    }

    /** Whether a session that hasn't ended takes this upload as one of its inputs. */
    boolean inUse(UUID id) {
        return jdbc.sql("select exists (select 1 from sessions.session_inputs i join sessions.sessions s"
                        + " on s.id = i.session_id where i.upload_id = :id and s.state not in (" + FINAL_STATES + "))")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /** Pending and ready uploads past their expiry, oldest first, locked and skipped if another instance has them. */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Upload> expired(Instant now, int limit) {
        return jdbc.sql("""
                        select * from sessions.uploads
                        where status in ('pending', 'ready') and expires_at <= :now
                        order by expires_at limit :limit
                        for update skip locked
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UploadStore::upload)
                .list();
    }

    private static Upload upload(ResultSet rs, int row) throws SQLException {
        var completedAt = rs.getTimestamp("completed_at");
        var lastUsedAt = rs.getTimestamp("last_used_at");
        return new Upload(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("name"),
                rs.getLong("size_bytes"),
                rs.getInt("file_count"),
                rs.getLong("part_size_bytes"),
                Status.valueOf(rs.getString("status").toUpperCase(java.util.Locale.ROOT)),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                lastUsedAt == null ? null : lastUsedAt.toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getInt("version"));
    }
}
