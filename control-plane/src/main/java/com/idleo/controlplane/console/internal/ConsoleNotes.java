package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminSubject;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.notifications.AccountEmails;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notes and tags on a user, host, session or payment (ADR 0052): the only writer of the console's schema. Every change
 * is audited in the same transaction. A note can be edited by its author for 10 minutes, then it's fixed.
 */
@Component
class ConsoleNotes {

    static final Duration EDITABLE_FOR = Duration.ofMinutes(10);

    record Note(
            UUID id,
            String subjectKind,
            UUID subjectId,
            UUID authorId,
            String body,
            boolean pinned,
            boolean openInvestigation,
            Instant createdAt,
            @Nullable Instant editedAt,
            long version) {}

    record Tag(String id, String label, String colour) {}

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final AccountEmails emails;
    private final AdminAccess access;
    private final Map<String, AdminSubject> subjects;

    ConsoleNotes(
            JdbcClient jdbc,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            AccountEmails emails,
            AdminAccess access,
            List<AdminSubject> subjects) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
        this.emails = emails;
        this.access = access;
        this.subjects = subjects.stream().collect(Collectors.toUnmodifiableMap(AdminSubject::kind, each -> each));
    }

    /** The person a record is about; the 404 of a record that isn't there. */
    private UUID personOf(String kind, UUID id) {
        var subject = subjects.get(kind);
        if (subject == null) {
            throw ApiProblem.invalid("subject", "must be user, host, session or payment.");
        }
        return subject.person(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such record."));
    }

    @Transactional
    Note add(
            CurrentUser admin,
            String kind,
            UUID subject,
            String body,
            boolean pinned,
            boolean openInvestigation,
            List<UUID> mentions) {
        var person = personOf(kind, subject);
        var id = ids.newId();
        var now = Instant.now(clock);
        jdbc.sql("""
                        insert into console.notes
                            (id, subject_kind, subject_id, subject_user_id, author_id, body, pinned, open_investigation,
                             created_at)
                        values (:id, :kind, :subject, :person, :author, :body, :pinned, :open, :now)
                        """)
                .param("id", id)
                .param("kind", kind)
                .param("subject", subject)
                .param("person", person)
                .param("author", admin.id())
                .param("body", body)
                .param("pinned", pinned)
                .param("open", openInvestigation)
                .param("now", Timestamp.from(now))
                .update();
        audit.record(new AuditTrail.Entry(
                audit.admin(admin),
                "note.add",
                kind,
                subject.toString(),
                "added a note",
                null,
                Map.of("noteId", id.toString(), "pinned", String.valueOf(pinned))));
        tell(admin, mentions, kind, subject, id);
        return find(id);
    }

    /** Emails each admin mentioned, by a link to the record: never the note's words, or whose it is. */
    private void tell(CurrentUser author, List<UUID> mentions, String kind, UUID subject, UUID note) {
        if (mentions.isEmpty()) {
            return;
        }
        var reachable =
                access.mentionable().stream().map(AdminAccess.Mentionable::id).collect(Collectors.toSet());
        for (var mentioned : Set.copyOf(mentions)) {
            if (!reachable.contains(mentioned)) {
                throw ApiProblem.invalid("mentions", "names someone who can't be reached.");
            }
            if (!mentioned.equals(author.id())) {
                emails.tell(
                        mentioned,
                        "note-mention:" + note + ":" + mentioned,
                        "You were mentioned in a note",
                        "Another admin mentioned you in a note in the console.",
                        pageOf(kind, subject));
            }
        }
    }

    private static String pageOf(String kind, UUID subject) {
        return "/admin/" + kind + "s/?id=" + subject + "&tab=notes";
    }

    @Transactional
    Note edit(CurrentUser admin, UUID id, String body) {
        var note = find(id);
        if (!note.authorId().equals(admin.id())) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "Only its author can change a note.");
        }
        var now = Instant.now(clock);
        if (now.isAfter(note.createdAt().plus(EDITABLE_FOR))) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "A note is fixed 10 minutes after it's written.");
        }
        jdbc.sql("update console.notes set body = :body, edited_at = :now, version = version + 1 where id = :id")
                .param("body", body)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
        audit.record(new AuditTrail.Entry(
                audit.admin(admin),
                "note.edit",
                note.subjectKind(),
                note.subjectId().toString(),
                "changed their note",
                null,
                Map.of("noteId", id.toString())));
        return find(id);
    }

    /** Pins or unpins a note, or closes its investigation, whoever wrote it. */
    @Transactional
    Note change(CurrentUser admin, UUID id, @Nullable Boolean pinned, @Nullable Boolean openInvestigation) {
        var note = find(id);
        jdbc.sql("""
                        update console.notes
                        set pinned = coalesce(:pinned, pinned), open_investigation = coalesce(:open, open_investigation),
                            version = version + 1
                        where id = :id
                        """)
                .param("pinned", pinned)
                .param("open", openInvestigation)
                .param("id", id)
                .update();
        audit.record(new AuditTrail.Entry(
                audit.admin(admin),
                "note.change",
                note.subjectKind(),
                note.subjectId().toString(),
                "pinned or closed a note",
                null,
                Map.of(
                        "noteId", id.toString(),
                        "pinned", String.valueOf(pinned),
                        "openInvestigation", String.valueOf(openInvestigation))));
        return find(id);
    }

    Note find(UUID id) {
        return jdbc.sql("select * from console.notes where id = :id")
                .param("id", id)
                .query(ConsoleNotes::note)
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such note."));
    }

    List<Note> on(String kind, UUID subject) {
        return jdbc.sql("""
                        select * from console.notes where subject_kind = :kind and subject_id = :subject
                        order by pinned desc, created_at desc
                        limit 200
                        """)
                .param("kind", kind)
                .param("subject", subject)
                .query(ConsoleNotes::note)
                .list();
    }

    static Note note(ResultSet rs, int row) throws SQLException {
        var edited = rs.getTimestamp("edited_at");
        return new Note(
                rs.getObject("id", UUID.class),
                rs.getString("subject_kind"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("author_id", UUID.class),
                rs.getString("body"),
                rs.getBoolean("pinned"),
                rs.getBoolean("open_investigation"),
                rs.getTimestamp("created_at").toInstant(),
                edited == null ? null : edited.toInstant(),
                rs.getLong("version"));
    }

    List<Tag> vocabulary() {
        return jdbc.sql("select id, label, colour from console.tags order by label")
                .query((rs, n) -> new Tag(rs.getString("id"), rs.getString("label"), rs.getString("colour")))
                .list();
    }

    List<Tag> tagsOn(String kind, UUID subject) {
        return jdbc.sql("""
                        select t.id, t.label, t.colour from console.tag_assignments a
                        join console.tags t on t.id = a.tag_id
                        where a.subject_kind = :kind and a.subject_id = :subject
                        order by t.label
                        """)
                .param("kind", kind)
                .param("subject", subject)
                .query((rs, n) -> new Tag(rs.getString("id"), rs.getString("label"), rs.getString("colour")))
                .list();
    }

    @Transactional
    void defineTag(CurrentUser admin, String id, String label, String colour) {
        jdbc.sql("""
                        insert into console.tags (id, label, colour, created_at) values (:id, :label, :colour, :now)
                        on conflict (id) do update set label = excluded.label, colour = excluded.colour
                        """)
                .param("id", id)
                .param("label", label)
                .param("colour", colour)
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
        audit.record(new AuditTrail.Entry(
                audit.admin(admin), "tag.define", "tag", id, "changed the tags", null, Map.of("label", label)));
    }

    @Transactional
    void assign(UUID admin, String kind, UUID subject, String tag) {
        var person = personOf(kind, subject);
        if (vocabulary().stream().noneMatch(known -> known.id().equals(tag))) {
            throw ApiProblem.invalid("tag", "isn't one of the Owner's tags.");
        }
        var added = jdbc.sql("""
                        insert into console.tag_assignments
                            (subject_kind, subject_id, tag_id, subject_user_id, added_by, added_at)
                        values (:kind, :subject, :tag, :person, :admin, :now)
                        on conflict do nothing
                        """)
                .param("kind", kind)
                .param("subject", subject)
                .param("tag", tag)
                .param("person", person)
                .param("admin", admin)
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
        if (added > 0) {
            audit.record(new AuditTrail.Entry(
                    new AuditTrail.Actor(AuditTrail.Actor.Kind.ADMIN, admin, admin.toString()),
                    "tag.add",
                    kind,
                    subject.toString(),
                    "added a tag",
                    null,
                    Map.of("tag", tag)));
        }
    }

    @Transactional
    void unassign(UUID admin, String kind, UUID subject, String tag) {
        var removed = jdbc.sql("""
                        delete from console.tag_assignments
                        where subject_kind = :kind and subject_id = :subject and tag_id = :tag
                        """)
                .param("kind", kind)
                .param("subject", subject)
                .param("tag", tag)
                .update();
        if (removed > 0) {
            audit.record(new AuditTrail.Entry(
                    new AuditTrail.Actor(AuditTrail.Actor.Kind.ADMIN, admin, admin.toString()),
                    "tag.remove",
                    kind,
                    subject.toString(),
                    "removed a tag",
                    null,
                    Map.of("tag", tag)));
        }
    }
}
