package com.idleo.controlplane.console.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Notes and tags on a user, host, session or payment (ADR 0052). */
@RestController
@ConditionalOnWebApplication
class AdminNotesController {

    private final ConsoleNotes notes;
    private final AdminAccess access;
    private final Clock clock;

    AdminNotesController(ConsoleNotes notes, AdminAccess access, Clock clock) {
        this.notes = notes;
        this.access = access;
        this.clock = clock;
    }

    /** One note. {@code body} is Markdown-lite: **bold**, *italic*, `code` and new lines; never HTML. */
    record AdminNote(
            UUID id,
            UUID authorId,
            String body,
            boolean pinned,
            boolean openInvestigation,
            Instant createdAt,
            @Nullable Instant editedAt,
            boolean editable,
            long version) {}

    record AdminNotes(List<AdminNote> notes) {}

    record AdminTag(
            String id,
            String label,

            @Schema(allowableValues = {"plain", "running", "danger"})
            String colour) {}

    record AdminTags(List<AdminTag> tags) {}

    record AdminMentionable(UUID id, String name) {}

    record AdminMentionables(List<AdminMentionable> admins) {}

    /**
     * @param openInvestigation it's written for a fraud investigation that's still open, so it stays out of the person's
     *     export until it's closed
     * @param mentions the admins to email a link to, who never get the note's words by email
     */
    record NewNote(
            @Schema(allowableValues = {"user", "host", "session", "payment"}) @NotBlank
            String subject,

            UUID id,
            @NotBlank @Size(max = 4000) String body,
            boolean pinned,
            boolean openInvestigation,
            @Nullable List<UUID> mentions) {}

    record NoteEdit(@NotBlank @Size(max = 4000) String body) {}

    record NoteChange(@Nullable Boolean pinned, @Nullable Boolean openInvestigation) {}

    record TagDefinition(
            @NotBlank @Size(max = 40) String id,
            @NotBlank @Size(max = 40) String label,

            @Schema(allowableValues = {"plain", "running", "danger"}) @NotBlank
            String colour) {

        TagDefinition {
            if (id != null && !id.matches("[a-z0-9-]+")) {
                throw ApiProblem.invalid("id", "must be lowercase letters, digits and dashes.");
            }
            if (colour != null && !List.of("plain", "running", "danger").contains(colour)) {
                throw ApiProblem.invalid("colour", "must be plain, running or danger.");
            }
        }
    }

    private AdminNote view(CurrentUser admin, ConsoleNotes.Note note) {
        return new AdminNote(
                note.id(),
                note.authorId(),
                note.body(),
                note.pinned(),
                note.openInvestigation(),
                note.createdAt(),
                note.editedAt(),
                note.authorId().equals(admin.id())
                        && Instant.now(clock).isBefore(note.createdAt().plus(ConsoleNotes.EDITABLE_FOR)),
                note.version());
    }

    @RequiresPermission(AdminPermission.NOTES_READ)
    @GetMapping("/admin/v1/notes")
    AdminNotes list(@AuthenticationPrincipal CurrentUser admin, @RequestParam String subject, @RequestParam UUID id) {
        return new AdminNotes(
                notes.on(subject, id).stream().map(note -> view(admin, note)).toList());
    }

    /** Adds a note. Audited; mentions are emailed. */
    @RequiresPermission(AdminPermission.NOTES_WRITE)
    @PostMapping("/admin/v1/notes")
    AdminNote add(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody NewNote request) {
        return view(
                admin,
                notes.add(
                        admin,
                        request.subject(),
                        request.id(),
                        request.body().strip(),
                        request.pinned(),
                        request.openInvestigation(),
                        request.mentions() == null ? List.of() : request.mentions()));
    }

    /** Changes a note's words: by its author, for 10 minutes. */
    @RequiresPermission(value = AdminPermission.NOTES_WRITE, record = "note")
    @PostMapping("/admin/v1/notes/{id}/edit")
    AdminNote edit(
            @AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id, @Valid @RequestBody NoteEdit request) {
        return view(admin, notes.edit(admin, id, request.body().strip()));
    }

    /** Pins or unpins it, or says its investigation is closed. */
    @RequiresPermission(value = AdminPermission.NOTES_WRITE, record = "note")
    @PostMapping("/admin/v1/notes/{id}/change")
    AdminNote change(
            @AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id, @RequestBody NoteChange request) {
        return view(admin, notes.change(admin, id, request.pinned(), request.openInvestigation()));
    }

    /** Who can be mentioned. */
    @RequiresPermission(AdminPermission.NOTES_WRITE)
    @GetMapping("/admin/v1/notes/mentionable")
    AdminMentionables mentionable() {
        return new AdminMentionables(access.mentionable().stream()
                .map(each -> new AdminMentionable(each.id(), each.name()))
                .toList());
    }

    private static AdminTags tags(List<ConsoleNotes.Tag> tags) {
        return new AdminTags(tags.stream()
                .map(tag -> new AdminTag(tag.id(), tag.label(), tag.colour()))
                .toList());
    }

    /** The Owner's vocabulary. */
    @RequiresPermission(AdminPermission.NOTES_READ)
    @GetMapping("/admin/v1/tags")
    AdminTags vocabulary() {
        return tags(notes.vocabulary());
    }

    /** Adds a tag to the vocabulary, or changes its name or colour. Audited. */
    @RequiresPermission(AdminPermission.TAGS_MANAGE)
    @PostMapping("/admin/v1/tags")
    AdminTags define(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody TagDefinition request) {
        notes.defineTag(admin, request.id(), request.label().strip(), request.colour());
        return tags(notes.vocabulary());
    }

    /** The tags on one record. */
    @RequiresPermission(AdminPermission.NOTES_READ)
    @GetMapping("/admin/v1/records/{subject}/{id}/tags")
    AdminTags tagsOn(@PathVariable String subject, @PathVariable UUID id) {
        return tags(notes.tagsOn(subject, id));
    }

    /** Puts a tag on a record. Audited. */
    @RequiresPermission(AdminPermission.NOTES_WRITE)
    @PostMapping("/admin/v1/records/{subject}/{id}/tags/{tag}")
    AdminTags assign(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable String subject,
            @PathVariable UUID id,
            @PathVariable String tag) {
        notes.assign(admin, subject, id, tag);
        return tags(notes.tagsOn(subject, id));
    }

    /** Takes a tag off. Audited. */
    @RequiresPermission(AdminPermission.NOTES_WRITE)
    @PostMapping("/admin/v1/records/{subject}/{id}/tags/{tag}/remove")
    AdminTags unassign(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable String subject,
            @PathVariable UUID id,
            @PathVariable String tag) {
        notes.unassign(admin, subject, id, tag);
        return tags(notes.tagsOn(subject, id));
    }
}
