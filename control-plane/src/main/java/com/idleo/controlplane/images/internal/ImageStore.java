package com.idleo.controlplane.images.internal;

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

/** Renters' submissions and their checks' state (ADR 0042): {@code images.images}. */
@Component
class ImageStore {

    /** Where a check stands. */
    enum State {
        QUEUED,
        CHECKING,
        READY,
        BLOCKED,
        FAILED;

        String db() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static State of(String db) {
            return valueOf(db.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** One submission. */
    record Row(
            UUID id,
            UUID userId,
            String reference,
            String registry,
            String repository,
            @Nullable UUID loginId,
            boolean isPrivate,
            @Nullable String digest,
            State state,
            @Nullable String reason,
            @Nullable String detail,
            int attempts,
            @Nullable String claimedBy,
            @Nullable Instant claimExpiresAt,
            @Nullable String phase,
            long doneBytes,
            long totalBytes,
            Instant createdAt,
            Instant updatedAt,
            @Nullable Instant checkedAt,
            @Nullable Instant lastUsedAt) {}

    private final JdbcClient jdbc;

    ImageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("reference"),
                rs.getString("registry"),
                rs.getString("repository"),
                rs.getObject("login_id", UUID.class),
                rs.getBoolean("private"),
                rs.getString("digest"),
                State.of(rs.getString("state")),
                rs.getString("reason"),
                rs.getString("detail"),
                rs.getInt("attempts"),
                rs.getString("claimed_by"),
                instant(rs, "claim_expires_at"),
                rs.getString("phase"),
                rs.getLong("done_bytes"),
                rs.getLong("total_bytes"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                instant(rs, "checked_at"),
                instant(rs, "last_used_at"));
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }

    void insert(Row row) {
        jdbc.sql("""
                        insert into images.images
                            (id, user_id, reference, registry, repository, login_id, private, state, created_at, updated_at)
                        values (:id, :userId, :reference, :registry, :repository, :loginId, :private, 'queued', :now, :now)
                        """)
                .param("id", row.id())
                .param("userId", row.userId())
                .param("reference", row.reference())
                .param("registry", row.registry())
                .param("repository", row.repository())
                .param("loginId", row.loginId())
                .param("private", row.isPrivate())
                .param("now", Timestamp.from(row.createdAt()))
                .update();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql("select * from images.images where id = :id")
                .param("id", id)
                .query(ImageStore::row)
                .optional();
    }

    /** Locks the row for the caller's transaction. */
    Optional<Row> lock(UUID id) {
        return jdbc.sql("select * from images.images where id = :id for update")
                .param("id", id)
                .query(ImageStore::row)
                .optional();
    }

    List<Row> ofUser(UUID userId) {
        return jdbc.sql("select * from images.images where user_id = :userId order by created_at desc, id desc")
                .param("userId", userId)
                .query(ImageStore::row)
                .list();
    }

    /** The submissions of {@code digest} that a block would change: everything not blocked already. */
    List<Row> withDigest(String digest) {
        return jdbc.sql("select * from images.images where digest = :digest and state <> 'blocked' for update")
                .param("digest", digest)
                .query(ImageStore::row)
                .list();
    }

    /** Ends a check: {@code state} with its reason and words, the digest it found, its progress cleared. */
    void finish(
            UUID id,
            State state,
            @Nullable String digest,
            @Nullable String reason,
            @Nullable String detail,
            Instant now) {
        jdbc.sql("""
                        update images.images
                        set state = :state, digest = coalesce(:digest, digest), reason = :reason, detail = :detail,
                            claimed_by = null, claim_expires_at = null, phase = null, done_bytes = 0, total_bytes = 0,
                            checked_at = :now, updated_at = :now
                        where id = :id
                        """)
                .param("state", state.db())
                .param("digest", digest)
                .param("reason", reason)
                .param("detail", detail)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    /** A check's progress, which also keeps its claim. False if the claim isn't {@code scanner}'s any more. */
    boolean progress(UUID id, String scanner, String phase, long done, long total, Instant now, Instant claimUntil) {
        return jdbc.sql("""
                        update images.images
                        set phase = :phase, done_bytes = :done, total_bytes = :total, claim_expires_at = :until,
                            updated_at = :now
                        where id = :id and state = 'checking' and claimed_by = :scanner
                        """)
                        .param("phase", phase)
                        .param("done", done)
                        .param("total", total)
                        .param("until", Timestamp.from(claimUntil))
                        .param("now", Timestamp.from(now))
                        .param("id", id)
                        .param("scanner", scanner)
                        .update()
                == 1;
    }

    void delete(UUID id) {
        jdbc.sql("delete from images.images where id = :id").param("id", id).update();
    }

    void used(UUID id, Instant now) {
        jdbc.sql("update images.images set last_used_at = :now where id = :id")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }
}
