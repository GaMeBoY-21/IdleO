package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTimeline;
import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.identity.AdminRecords;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the console holds about a person (ADR 0052, docs/reference/personal-data-map.md): the notes and tags about them,
 * which their export includes, LEGAL(confirm), except notes written for a fraud investigation while it's open. Erasing
 * deletes them. It also gives the timeline its notes, and the versions a note's edits are checked against.
 */
@Configuration(proxyBeanMethods = false)
class ConsoleOwnParts implements PersonalData {

    private final JdbcClient jdbc;
    private final ExportRows rows;

    ConsoleOwnParts(JdbcClient jdbc, ExportRows rows) {
        this.jdbc = jdbc;
        this.rows = rows;
    }

    @Override
    public String module() {
        return "console";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of(jdbc.sql("""
                        select count(*), min(created_at), max(created_at) from console.notes
                        where subject_user_id = :user and not open_investigation
                        """)
                .param("user", userId)
                .query((rs, n) -> new Group(
                        "account",
                        "notes by staff about you",
                        rs.getLong(1),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()))
                .single());
    }

    @Override
    public List<Part> export(UUID userId) {
        return List.of(
                new Part("account", "staffNotes", rows.list(jdbc, """
                                select subject_kind, subject_id, author_id, body, created_at, edited_at
                                from console.notes
                                where subject_user_id = :user and not open_investigation
                                order by created_at
                                """, Map.of("user", userId))),
                new Part("account", "staffTags", rows.list(jdbc, """
                                select subject_kind, subject_id, tag_id, added_by, added_at
                                from console.tag_assignments
                                where subject_user_id = :user
                                order by added_at
                                """, Map.of("user", userId))));
    }

    @Override
    @Transactional
    public void erase(UUID userId, ErasureStep step) {
        if (step != ErasureStep.DELETE_RECORDS) {
            return;
        }
        jdbc.sql("delete from console.notes where subject_user_id = :user")
                .param("user", userId)
                .update();
        jdbc.sql("delete from console.tag_assignments where subject_user_id = :user")
                .param("user", userId)
                .update();
        jdbc.sql("delete from console.saved_views where owner_id = :user")
                .param("user", userId)
                .update();
    }

    @Bean
    AdminRecords consoleAdminRecords() {
        return () -> Map.of("note", AdminRecords.byId(jdbc, "id", "select version from console.notes where id = :id"));
    }

    @Bean
    AdminTimeline notesTimeline() {
        return AdminTimeline.of(
                "note",
                AdminPermission.NOTES_READ,
                (subject, window) -> jdbc.sql("""
                        select id, body, pinned, created_at from console.notes
                        where subject_kind = :kind and subject_id = :id and created_at <= :until
                        order by created_at desc
                        limit :limit
                        """)
                        .param("kind", subject.kind())
                        .param("id", subject.id())
                        .param("until", Timestamp.from(window.until()))
                        .param("limit", window.limit())
                        .query((rs, n) -> new AdminTimeline.TimelineEvent(
                                "note:" + rs.getString("id"),
                                rs.getTimestamp("created_at").toInstant(),
                                "note",
                                rs.getBoolean("pinned") ? "Pinned note" : "Note",
                                rs.getString("body"),
                                null,
                                null))
                        .list());
    }
}
