package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Broadcast notices (ADR 0056): scheduled by an approved request, listed on the list engine, cancelled before they go.
 */
@RestController
@ConditionalOnWebApplication
class AdminBroadcastsController {

    static final String SCHEDULE = "broadcast.schedule";

    private final Broadcasts broadcasts;
    private final Approvals approvals;
    private final AuditTrail audit;
    private final JdbcClient jdbc;

    AdminBroadcastsController(Broadcasts broadcasts, Approvals approvals, AuditTrail audit, JdbcClient jdbc) {
        this.broadcasts = broadcasts;
        this.approvals = approvals;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * A broadcast.
     *
     * @param audience renters, hosts, tag (with {@code tag}) or agent_versions (with a lowest or highest version, or
     *     both)
     * @param sendAt when it goes; now when left out
     * @param email also by email; it's always in the app
     */
    record AdminBroadcastRequest(
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 10000) String body,
            @NotNull Broadcasts.Audience audience,
            @Nullable @Size(max = 60) String tag,
            @Nullable @Size(max = 40) String minAgentVersion,
            @Nullable @Size(max = 40) String maxAgentVersion,
            boolean email,
            @Nullable Instant sendAt,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    record AdminBroadcast(
            UUID id,
            String subject,
            String body,
            String audience,
            @Nullable String tag,
            @Nullable String minAgentVersion,
            @Nullable String maxAgentVersion,
            boolean email,
            Instant sendAt,
            String state,
            @Nullable Integer reached,
            UUID createdBy,
            String reason,
            Instant createdAt,
            @Nullable Instant sentAt,
            long version) {}

    record AdminBroadcastPage(
            List<AdminBroadcast> items, @Nullable String nextCursor) {}

    /** Asks to schedule it: 202 with the request, which another admin approves; it goes at its time once approved. */
    @RequiresPermission(AdminPermission.BROADCASTS_SEND)
    @PostMapping("/admin/v1/broadcasts")
    ResponseEntity<Approvals.AdminApproval> schedule(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminBroadcastRequest request) {
        var asked = new Broadcasts.Asked(
                request.subject(),
                request.body(),
                request.audience(),
                request.tag(),
                request.minAgentVersion(),
                request.maxAgentVersion(),
                request.email(),
                request.sendAt(),
                request.reason().strip());
        broadcasts.check(asked);
        return approvals.submit(
                admin,
                SCHEDULE,
                null,
                null,
                asked,
                List.of(new Approvals.ApprovalChange(
                        "a notice to " + request.audience().db()
                                + (request.email() ? ", by email too" : ", in the app"),
                        null,
                        request.subject())),
                request.reason());
    }

    @RequiresPermission(AdminPermission.BROADCASTS_SEND)
    @AdminList("broadcasts")
    @GetMapping("/admin/v1/broadcasts")
    AdminBroadcastPage list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page =
                Broadcasts.LIST.page(jdbc, AdminListing.Asked.of(sort, "state", state), cursor, limit, Broadcasts::row);
        return new AdminBroadcastPage(
                page.rows().stream().map(AdminBroadcastsController::view).toList(), page.nextCursor());
    }

    @RequiresPermission(value = AdminPermission.BROADCASTS_SEND, record = "broadcast")
    @PostMapping("/admin/v1/broadcasts/{id}/cancel")
    AdminBroadcast cancel(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return view(broadcasts.cancel(id, request.reason(), audit.admin(admin)));
    }

    private static AdminBroadcast view(Broadcasts.Broadcast row) {
        return new AdminBroadcast(
                row.id(),
                row.subject(),
                row.body(),
                row.audience(),
                row.tag(),
                row.minAgentVersion(),
                row.maxAgentVersion(),
                row.email(),
                row.sendAt(),
                row.state(),
                row.reached(),
                row.createdBy(),
                row.reason(),
                row.createdAt(),
                row.sentAt(),
                row.version());
    }
}
