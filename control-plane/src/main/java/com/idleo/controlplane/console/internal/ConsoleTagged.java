package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminTagged;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** What carries a tag, for any list's {@code tag} filter (ADR 0052). */
@Component
class ConsoleTagged implements AdminTagged {

    /** An id no row has: a filter by a tag nothing carries finds nothing. */
    private static final UUID NOTHING = new UUID(0, 0);

    private final JdbcClient jdbc;
    private final ConsoleNotes notes;

    ConsoleTagged(JdbcClient jdbc, ConsoleNotes notes) {
        this.jdbc = jdbc;
        this.notes = notes;
    }

    @Override
    public boolean carries(String kind, UUID subject, String tag) {
        return jdbc.sql("""
                        select count(*) from console.tag_assignments
                        where subject_kind = :kind and subject_id = :subject and tag_id = :tag
                        """)
                        .param("kind", kind)
                        .param("subject", subject)
                        .param("tag", tag)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    public void add(String kind, UUID subject, String tag, UUID admin) {
        notes.assign(admin, kind, subject, tag);
    }

    @Override
    public void remove(String kind, UUID subject, String tag, UUID admin) {
        notes.unassign(admin, kind, subject, tag);
    }

    @Override
    public @Nullable List<UUID> subjects(String kind, @Nullable String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        // A person's tag also finds what's theirs: a list of payouts, by the people who carry it.
        var column = kind.equals("user") ? "subject_user_id" : "subject_id";
        var found = jdbc.sql("select distinct " + column + " from console.tag_assignments"
                        + " where tag_id = :tag and subject_kind = :kind limit :most")
                .param("tag", tag.strip())
                .param("kind", kind)
                .param("most", MOST)
                .query(UUID.class)
                .list();
        return found.isEmpty() ? List.of(NOTHING) : found;
    }
}
