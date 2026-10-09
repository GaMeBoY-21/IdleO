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

/** Upload rows (ADR 0019), {@code sessions.uploads}. */
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

    record Upload(
            UUID id,
            UUID userId,
            String filename,
            String storedName,
            long sizeBytes,
            String objectKey,
            @Nullable String multipartId,
            long partSizeBytes,
            Status status,
            String idempotencyKey,
            String requestHash,
            Instant createdAt,
            @Nullable Instant completedAt,
            Instant expiresAt,
            int version) {

        /** How many parts it uploads in: the last may be smaller than the rest, and a one-part upload is multipart. */
        int partCount() {
            return Math.toIntExact((sizeBytes + partSizeBytes - 1) / partSizeBytes);
        }

        /** Its folder in storage, which a session's read grant covers. */
        String prefix() {
            return objectKey.substring(0, objectKey.length() - storedName.length());
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
                                    (id, user_id, filename, stored_name, size_bytes, object_key, multipart_id,
                                     part_size_bytes, status, idempotency_key, request_hash, created_at, completed_at,
                                     expires_at, version)
                                values (:id, :userId, :filename, :storedName, :size, :key, :multipartId, :partSize,
                                        :status, :idempotencyKey, :hash, :createdAt, null, :expiresAt, 0)
                                on conflict (user_id, idempotency_key) do nothing
                                """)
                        .param("id", upload.id())
                        .param("userId", upload.userId())
                        .param("filename", upload.filename())
                        .param("storedName", upload.storedName())
                        .param("size", upload.sizeBytes())
                        .param("key", upload.objectKey())
                        .param("multipartId", upload.multipartId())
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
                            multipart_id = case when :status = 'pending' then multipart_id end, version = version + 1
                        where id = :id
                        """)
                .param("status", status.name().toLowerCase(java.util.Locale.ROOT))
                .param("completedAt", completedAt == null ? null : Timestamp.from(completedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("id", id)
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
        return new Upload(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("filename"),
                rs.getString("stored_name"),
                rs.getLong("size_bytes"),
                rs.getString("object_key"),
                rs.getString("multipart_id"),
                rs.getLong("part_size_bytes"),
                Status.valueOf(rs.getString("status").toUpperCase(java.util.Locale.ROOT)),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getInt("version"));
    }
}
