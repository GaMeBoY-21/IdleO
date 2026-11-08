package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.RevealableFields;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's data, whole, one field at a time (ADR 0051): the one reveal, needing {@code users.pii.reveal}, a recent
 * factor and a reason, and the access log of who viewed or revealed what.
 */
@RestController
@RequestMapping("/admin/v1/personal-data")
@ConditionalOnWebApplication
class AdminPersonalDataController {

    /** A reason is at least this long, or a ticket's number. */
    static final int REASON_MIN = 10;

    /** Who read whose personal data (ADR 0051), its filters, orders and CSV (ADR 0052). */
    static final AdminListing ACCESS_LOG = AdminListing.of("access-log", AdminPermission.AUDIT_READ, true)
            .noBulk("It's a record of what happened: nothing in it changes.")
            .select("id, admin_id, subject_user_id, field, reason, admin_session_id, at")
            .from("identity.personal_data_access")
            .sort("at", AdminListing.Type.UUID, "id")
            .sort("field", AdminListing.Type.TEXT, "field")
            .sortedBy("at", true)
            .filter("subject", "subject_user_id = cast(:subject as uuid)")
            .filter("admin", "admin_id = cast(:admin as uuid)")
            .csv("id", "id")
            .csv("adminId", "admin_id")
            .csv("subjectUserId", "subject_user_id")
            .csv("field", "field")
            .csv("reason", "reason")
            .csv("at", "at")
            .build();

    private final Map<String, Function<String, Optional<RevealableFields.Revealed>>> fields;
    private final AccessLog accessLog;
    private final JdbcClient jdbc;

    AdminPersonalDataController(List<RevealableFields> modules, AccessLog accessLog, JdbcClient jdbc) {
        var all = new HashMap<String, Function<String, Optional<RevealableFields.Revealed>>>();
        modules.forEach(module -> module.fields().forEach((name, read) -> {
            if (all.put(name, read) != null) {
                throw new IllegalStateException(name + " is revealed by two modules");
            }
        }));
        this.fields = Map.copyOf(all);
        this.accessLog = accessLog;
        this.jdbc = jdbc;
    }

    /**
     * Which value, by its field ({@code email}, {@code pan}, {@code upi}, {@code reply_to}, {@code security_event_ip} or
     * {@code audit_ip}) and the id of the record holding it, and why: at least 10 characters, or a ticket's number.
     */
    record RevealRequest(
            @NotBlank @Size(max = 40) String field,
            @NotBlank @Size(max = 64) String id,

            @NotBlank @Size(max = 500) @NoContactDetails String reason) {}

    /** The whole value, for this one field; it's never kept in the console. */
    record AdminRevealed(String field, String value) {}

    /**
     * One entry: which admin, whose data, which field ({@code view} for opening their page), why, when and from which
     * admin session.
     */
    record AccessLogEntry(
            UUID id,
            UUID adminId,
            @Nullable UUID subjectUserId,
            String field,
            String reason,
            @Nullable UUID adminSessionId,
            Instant at) {}

    record AccessLogPage(
            List<AccessLogEntry> entries, @Nullable String nextCursor) {}

    /** Reads one field whole. Writes the access log and the audit log. 404 when there's no such value. */
    @RequiresPermission(value = AdminPermission.USERS_PII_REVEAL, unmasked = true, shownOnce = true)
    @PostMapping("/reveal")
    AdminRevealed reveal(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody RevealRequest request) {
        var reason = request.reason().strip();
        if (reason.length() < REASON_MIN && !reason.matches("#?[0-9]+")) {
            throw ApiProblem.invalid("reason", "must be at least 10 characters, or a ticket's number.");
        }
        var read = fields.get(request.field());
        if (read == null) {
            throw ApiProblem.invalid("field", "isn't a field that can be revealed.");
        }
        var found =
                read.apply(request.id()).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such value."));
        accessLog.revealed(admin, found.subject(), request.field(), request.id(), reason);
        return new AdminRevealed(request.field(), found.value());
    }

    /** The access log, newest first; {@code subject} and {@code admin} narrow it to one person or one admin. */
    @RequiresPermission(AdminPermission.AUDIT_READ)
    @AdminList("access-log")
    @GetMapping("/access-log")
    AccessLogPage accessLog(
            @RequestParam(required = false) @Nullable UUID subject,
            @RequestParam(required = false) @Nullable UUID admin,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = ACCESS_LOG.page(
                jdbc,
                AdminListing.Asked.of(sort, "subject", subject, "admin", admin),
                cursor,
                limit,
                (rs, n) -> new AccessLogEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("admin_id", UUID.class),
                        rs.getObject("subject_user_id", UUID.class),
                        rs.getString("field"),
                        rs.getString("reason"),
                        rs.getObject("admin_session_id", UUID.class),
                        rs.getTimestamp("at").toInstant()));
        return new AccessLogPage(page.rows(), page.nextCursor());
    }
}
