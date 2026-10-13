package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invite codes, for the admin console (ADR 0024, ADR 0027): made by the same service as {@code create-invites},
 * shown once, and listed with who used each. A code itself is never stored, so the list never shows one.
 */
@RestController
@RequestMapping("/admin/v1/invites")
@ConditionalOnWebApplication
class AdminInvitesController {

    private final InviteService invites;
    private final IdempotentRequests idempotency;
    private final AuditTrail audit;
    private final JdbcClient jdbc;

    AdminInvitesController(InviteService invites, IdempotentRequests idempotency, AuditTrail audit, JdbcClient jdbc) {
        this.invites = invites;
        this.idempotency = idempotency;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * {@code count} codes, from 1 to 200; {@code note} says who they're for and is the audit log's reason; each code
     * lapses {@code expiresInDays} days from now, or never when it isn't given.
     */
    record AdminInviteRequest(
            @Min(1) @Max(InviteService.MAX_COUNT) int count,
            @NotBlank @Size(min = 5, max = 200) String note,
            @Min(1) @Max(365) @Nullable Integer expiresInDays) {}

    /** The new codes, shown only now; each works once. */
    record AdminInvitesCreated(List<String> codes, @Nullable Instant expiresAt) {}

    /** A code, without the code: its note, who made it and when, when it lapses, and who used it. */
    record AdminInvite(
            UUID id,
            String note,
            String createdBy,
            Instant createdAt,
            @Nullable Instant expiresAt,
            @Nullable UUID usedBy,
            @Nullable String usedByEmail,
            @Nullable Instant usedAt) {}

    record AdminInvitePage(
            List<AdminInvite> invites, @Nullable String nextCursor) {}

    @PostMapping
    @ApiResponse(
            responseCode = "201",
            description = "The codes, shown once; a repeat of the request is 409 secret-shown-once",
            content = @Content(schema = @Schema(implementation = AdminInvitesCreated.class)))
    ResponseEntity<String> create(
            @AuthenticationPrincipal CurrentUser admin,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody AdminInviteRequest request) {
        return idempotency.executeShownOnce(
                ApiKeyController.IDEMPOTENCY_TABLE, admin.id(), idempotencyKey, request, () -> {
                    var expiry = Optional.ofNullable(request.expiresInDays()).map(Duration::ofDays);
                    var created = invites.create(request.count(), request.note().strip(), expiry, audit.admin(admin));
                    return ResponseEntity.created(URI.create("/admin/v1/invites"))
                            .body(new AdminInvitesCreated(created.codes(), created.expiresAt()));
                });
    }

    /** Every code, newest first; {@code used} true or false keeps only the used or the unused ones. */
    @GetMapping
    AdminInvitePage list(
            @RequestParam(required = false) @Nullable Boolean used,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        var rows = jdbc.sql("""
                        select i.id, i.note, i.created_by, i.created_at, i.expires_at, i.used_by, i.used_at, u.email
                        from identity.invite_codes i left join identity.users u on u.id = i.used_by
                        where (cast(:used as boolean) is null or (i.used_at is not null) = cast(:used as boolean))
                          and (cast(:after as uuid) is null or i.id < cast(:after as uuid))
                        order by i.id desc
                        limit :limit
                        """)
                .param("used", used)
                .param("after", AdminPages.after(cursor))
                .param("limit", size + 1)
                .query((rs, n) -> new AdminInvite(
                        rs.getObject("id", UUID.class),
                        rs.getString("note"),
                        rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at") == null
                                ? null
                                : rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("used_by", UUID.class),
                        rs.getString("email"),
                        rs.getTimestamp("used_at") == null
                                ? null
                                : rs.getTimestamp("used_at").toInstant()))
                .list();
        var page = rows.size() > size ? rows.subList(0, size) : rows;
        return new AdminInvitePage(
                List.copyOf(page),
                rows.size() > size ? AdminPages.cursorAfter(page.getLast().id()) : null);
    }
}
