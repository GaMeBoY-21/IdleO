package com.idleo.controlplane.sessions.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The files of each upload (ADR 0022), {@code sessions.upload_files}. */
@Component
class UploadFileStore {

    /**
     * One file of an upload.
     *
     * @param path as the renter gave it, relative to the upload
     * @param storedPath its path in storage under the upload's {@code files/} folder, and in the job's input folder
     * @param objectKey where it is in storage
     * @param multipartId its multipart upload, once its first part link was asked for and until it's complete
     * @param completedAt when it was complete at its declared size; null until then
     */
    record UploadFile(
            UUID uploadId,
            String path,
            String storedPath,
            long sizeBytes,
            String objectKey,
            @Nullable String multipartId,
            @Nullable Instant completedAt) {

        /** How many parts it uploads in: the last may be smaller than the rest; an empty file has none. */
        int partCount(long partSizeBytes) {
            return Math.toIntExact((sizeBytes + partSizeBytes - 1) / partSizeBytes);
        }

        boolean done() {
            return completedAt != null;
        }

        /** The folder its object key is relative to, for the agent's InputRef. */
        String prefix() {
            return objectKey.substring(0, objectKey.length() - storedPath.length());
        }
    }

    private final JdbcClient jdbc;

    UploadFileStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void insert(List<UploadFile> files) {
        for (var file : files) {
            jdbc.sql("""
                            insert into sessions.upload_files (upload_id, path, stored_path, size_bytes, object_key)
                            values (:uploadId, :path, :storedPath, :size, :key)
                            """)
                    .param("uploadId", file.uploadId())
                    .param("path", file.path())
                    .param("storedPath", file.storedPath())
                    .param("size", file.sizeBytes())
                    .param("key", file.objectKey())
                    .update();
        }
    }

    /** The upload's files, by stored path. */
    List<UploadFile> files(UUID uploadId) {
        return jdbc.sql("select * from sessions.upload_files where upload_id = :uploadId order by stored_path")
                .param("uploadId", uploadId)
                .query(UploadFileStore::file)
                .list();
    }

    /** The upload's file at {@code path}, as the renter gave it, locked for the rest of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UploadFile> lock(UUID uploadId, String path) {
        return jdbc.sql("select * from sessions.upload_files where upload_id = :uploadId and path = :path for update")
                .param("uploadId", uploadId)
                .param("path", path)
                .query(UploadFileStore::file)
                .optional();
    }

    /** The file's multipart upload is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    void opened(UploadFile file, String multipartId) {
        jdbc.sql("""
                        update sessions.upload_files set multipart_id = :multipartId
                        where upload_id = :uploadId and stored_path = :storedPath
                        """)
                .param("multipartId", multipartId)
                .param("uploadId", file.uploadId())
                .param("storedPath", file.storedPath())
                .update();
    }

    /** The file is complete at its declared size; its multipart upload is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    void completed(UploadFile file, Instant at) {
        jdbc.sql("""
                        update sessions.upload_files set multipart_id = null, completed_at = :at
                        where upload_id = :uploadId and stored_path = :storedPath
                        """)
                .param("at", Timestamp.from(at))
                .param("uploadId", file.uploadId())
                .param("storedPath", file.storedPath())
                .update();
    }

    private static UploadFile file(ResultSet rs, int row) throws SQLException {
        var completedAt = rs.getTimestamp("completed_at");
        return new UploadFile(
                rs.getObject("upload_id", UUID.class),
                rs.getString("path"),
                rs.getString("stored_path"),
                rs.getLong("size_bytes"),
                rs.getString("object_key"),
                rs.getString("multipart_id"),
                completedAt == null ? null : completedAt.toInstant());
    }
}
