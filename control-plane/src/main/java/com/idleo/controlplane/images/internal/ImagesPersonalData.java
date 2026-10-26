package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.PersonalData;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What the images module holds about a renter (ADR 0042, ADR 0037, docs/reference/personal-data-map.md): the images they
 * submitted with their checks, and the registries they gave a token for (the user name and when, never the token). Erasure
 * deletes both, and what a check found for a digest no other renter's image names (a private image's file paths and
 * config are the renter's).
 */
@Component
class ImagesPersonalData implements PersonalData {

    private final JdbcClient jdbc;
    private final ExportRows rows;

    ImagesPersonalData(JdbcClient jdbc, ExportRows rows) {
        this.jdbc = jdbc;
        this.rows = rows;
    }

    @Override
    public String module() {
        return "images";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of(jdbc.sql(
                        "select count(*), min(created_at), max(created_at) from images.images where user_id = :user")
                .param("user", userId)
                .query((rs, n) -> new Group(
                        "files", "images", rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .single());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public List<Part> export(UUID userId) {
        var params = Map.of("user", userId);
        return List.of(
                new Part("files", "images", rows.list(jdbc, """
                                select id, reference, private, digest, state, reason, detail, created_at, checked_at,
                                       last_used_at
                                from images.images where user_id = :user order by created_at
                                """, params)),
                new Part("files", "registryLogins", rows.list(jdbc, """
                                select registry, username, created_at, last_used_at
                                from images.registry_logins where user_id = :user order by created_at
                                """, params)));
    }

    @Override
    public void erase(UUID userId, ErasureStep step) {
        if (step != ErasureStep.DELETE_RECORDS) {
            return;
        }
        var digests = jdbc.sql("select distinct digest from images.images where user_id = :user and digest is not null")
                .param("user", userId)
                .query(String.class)
                .list();
        jdbc.sql("delete from images.images where user_id = :user")
                .param("user", userId)
                .update();
        jdbc.sql("delete from images.registry_logins where user_id = :user")
                .param("user", userId)
                .update();
        if (!digests.isEmpty()) {
            jdbc.sql("""
                            delete from images.scans s
                            where s.digest in (:digests)
                              and not exists (select 1 from images.images i where i.digest = s.digest)
                            """).param("digests", digests).update();
        }
    }
}
