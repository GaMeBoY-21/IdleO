package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.billing.SessionRefunds;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Renters' reports, for the admin console (ADR 0028): the queue, open first and oldest first; one report with what
 * its session cost and who kept what, from the ledger; and resolving it once, with a refund or without, and a reason
 * the renter reads. Audited. The session's timeline is {@code GET /admin/v1/sessions/{id}}, and its minutes as both
 * witnesses saw them {@code GET /admin/v1/sessions/{id}/usage}.
 */
@RestController
@RequestMapping("/admin/v1/disputes")
@ConditionalOnWebApplication
class AdminDisputesController {

    /** The queue, open ones first and then the oldest; its filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("reports", AdminPermission.DISPUTES_READ, true)
            .from("trust.disputes")
            .sort("queue", AdminListing.Type.TEXT, "case when state = 'open' then '0' else '1' end")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sortedBy("queue", false)
            .filter("open", "(state = 'open') = cast(:open as boolean)")
            .filter("session", "session_id = cast(:session as uuid)")
            .filter("renter", "renter_id = cast(:renter as uuid)")
            .filter("tagged", "renter_id in (:tagged)")
            .csv("id", "id")
            .csv("sessionId", "session_id")
            .csv("renterId", "renter_id")
            .csv("reason", "reason")
            .csv("state", "state")
            .csv("refundPaise", "refund_paise")
            .csv("createdAt", "created_at")
            .csv("resolvedAt", "resolved_at")
            .build();

    private final Disputes disputes;
    private final EndedSessions sessions;
    private final Ratings ratings;
    private final SessionRefunds refunds;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final AdminTagged tagged;
    private final Approvals approvals;

    AdminDisputesController(
            Disputes disputes,
            EndedSessions sessions,
            Ratings ratings,
            SessionRefunds refunds,
            AuditTrail audit,
            JdbcClient jdbc,
            Approvals approvals,
            AdminTagged tagged) {
        this.tagged = tagged;
        this.approvals = approvals;
        this.disputes = disputes;
        this.sessions = sessions;
        this.ratings = ratings;
        this.refunds = refunds;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * A report: whose session, why, and how it was resolved.
     *
     * @param resolutionReason what the renter reads
     * @param resolvedBy the admin, or operator, who resolved it
     */
    record AdminDisputeSummary(
            UUID id,
            UUID sessionId,
            UUID renterId,
            String reason,
            @Nullable String details,
            String state,
            Instant createdAt,
            @Nullable String resolutionReason,
            @Nullable String resolvedBy,
            @Nullable Instant resolvedAt,
            @Nullable Long refundPaise,
            @Nullable Long commissionRefundPaise,
            @Nullable Long hostRefundPaise,
            @Nullable UUID refundTxn,
            long version) {}

    record AdminDisputePage(
            List<AdminDisputeSummary> disputes, @Nullable String nextCursor) {}

    /** What one host kept of the session's charge. */
    record AdminHostKept(UUID hostId, long paise) {}

    /**
     * The session as the report saw it end, what it cost and who kept what, now, from the ledger.
     *
     * @param hostId the host of its last attempt that ran; empty if it never ran
     * @param paidPaise what the renter paid, less everything given back, and so the most a refund can be
     * @param commissionPaise what the platform kept of it, which a refund comes out of first
     */
    record AdminDisputeSession(
            String state,
            @Nullable UUID hostId,
            @Nullable Instant startedAt,
            Instant endedAt,
            long paidPaise,
            long commissionPaise,
            List<AdminHostKept> hosts) {}

    /** The session's rating, if its renter gave one. */
    record AdminDisputeRating(int stars, @Nullable String comment, Instant ratedAt) {}

    /** A mark an admin put on a host's record when resolving a report on one of its sessions (ADR 0039). */
    record AdminHostFlag(UUID id, UUID hostId, UUID disputeId, String reason, String flaggedBy, Instant createdAt) {}

    record AdminHostFlags(
            List<AdminHostFlag> flags, @Nullable String nextCursor) {}

    /** @param hostFlags every flag on the session's host's record, the newest first, this report's included */
    record AdminDispute(
            AdminDisputeSummary dispute,
            AdminDisputeSession session,
            @Nullable AdminDisputeRating rating,
            List<AdminHostFlag> hostFlags) {}

    /**
     * @param amountPaise refunded to the renter's credit: more than zero, at most what the session cost net
     * @param reason what the renter reads, 5 to 500 characters; it's the audit row's reason too
     */
    record AdminRefund(
            @NotNull @Min(1) Long amountPaise,
            @NotBlank @Size(min = 5, max = 500) String reason,
            @Nullable Boolean flagHost) {

        AdminRefund {
            reason = reason == null ? null : reason.strip();
        }
    }

    /**
     * @param reason what the renter reads, 5 to 500 characters; it's the audit row's reason too
     * @param flagHost whether to mark the session's host's record too
     */
    record AdminNoRefund(
            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason,

            @Nullable Boolean flagHost) {

        AdminNoRefund {
            reason = reason == null ? null : reason.strip();
        }
    }

    /** {@code state} is {@code open}, {@code resolved} or {@code all} (the default). */
    @RequiresPermission(AdminPermission.DISPUTES_READ)
    @AdminList("reports")
    @GetMapping
    AdminDisputePage list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable UUID session,
            @RequestParam(required = false) @Nullable UUID renter,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var which = state == null || state.isBlank() ? "all" : state.strip();
        if (!List.of("open", "resolved", "all").contains(which)) {
            throw ApiProblem.invalid("state", "must be open, resolved or all.");
        }
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(
                        sort,
                        "open",
                        which.equals("all") ? null : Boolean.valueOf(which.equals("open")),
                        "session",
                        session,
                        "renter",
                        renter,
                        "tagged",
                        tagged.subjects("user", tag)),
                cursor,
                limit,
                AdminDisputesController::summary);
        return new AdminDisputePage(page.rows(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.DISPUTES_READ)
    @GetMapping("/{id}")
    AdminDispute get(@PathVariable UUID id) {
        var dispute = jdbc.sql("select * from trust.disputes where id = :id")
                .param("id", id)
                .query(AdminDisputesController::summary)
                .optional()
                .orElseThrow(AdminDisputesController::notFound);
        var ended = sessions.find(dispute.sessionId()).orElseThrow();
        var paid = refunds.paid(dispute.sessionId());
        var rated = ratings.given(dispute.sessionId());
        return new AdminDispute(
                dispute,
                new AdminDisputeSession(
                        ended.state(),
                        ended.hostId(),
                        ended.startedAt(),
                        ended.endedAt(),
                        paid.renterPaid().value(),
                        paid.commission().value(),
                        paid.hosts().stream()
                                .map(host -> new AdminHostKept(
                                        host.hostId(), host.amount().value()))
                                .toList()),
                rated == null ? null : new AdminDisputeRating(rated.stars(), rated.comment(), rated.ratedAt()),
                ended.hostId() == null ? List.of() : flags(ended.hostId()));
    }

    /** Every flag on a host's record, the newest first. */
    @RequiresPermission(AdminPermission.DISPUTES_READ)
    @AdminList("host-flags")
    @GetMapping("/host-flags")
    AdminHostFlags hostFlags(
            @RequestParam UUID host,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = HOST_FLAGS.page(
                jdbc, AdminListing.Asked.of(sort, "host", host), cursor, limit, AdminDisputesController::flag);
        return new AdminHostFlags(page.rows(), page.nextCursor());
    }

    /** Hosts flagged by a decision, the orders and CSV (ADR 0052). */
    static final AdminListing HOST_FLAGS = AdminListing.of("host-flags", AdminPermission.DISPUTES_READ, true)
            .from("trust.host_flags")
            .sort("createdAt", AdminListing.Type.TIME, "created_at")
            .sortedBy("createdAt", true)
            .filter("host", "host_id = cast(:host as uuid)")
            .csv("id", "id")
            .csv("hostId", "host_id")
            .csv("disputeId", "dispute_id")
            .csv("reason", "reason")
            .csv("flaggedBy", "flagged_by")
            .csv("createdAt", "created_at")
            .build();

    private List<AdminHostFlag> flags(UUID hostId) {
        return jdbc.sql("select * from trust.host_flags where host_id = :hostId order by created_at desc, id desc")
                .param("hostId", hostId)
                .query(AdminDisputesController::flag)
                .list();
    }

    private static AdminHostFlag flag(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AdminHostFlag(
                rs.getObject("id", UUID.class),
                rs.getObject("host_id", UUID.class),
                rs.getObject("dispute_id", UUID.class),
                rs.getString("reason"),
                rs.getString("flagged_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * Resolves the report with a refund to the renter's credit, from the platform's commission first and then the
     * hosts' shares. 409 {@code action-refused} for more than the session cost net, or a report already resolved
     * another way; the same refund again changes nothing.
     */
    @RequiresPermission(value = AdminPermission.DISPUTES_REFUND, record = "dispute")
    @PostMapping("/{id}/refund")
    @ApiResponse(
            responseCode = "200",
            description = "The report",
            content = @Content(schema = @Schema(implementation = AdminDispute.class)))
    @ApiResponse(
            responseCode = "202",
            description = "Above the limit, so it goes to another admin",
            content = @Content(schema = @Schema(implementation = Approvals.AdminApproval.class)))
    ResponseEntity<?> refund(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminRefund request) {
        var amount = Paise.of(request.amountPaise());
        if (approvals.refundNeedsApproval(amount)) {
            var dispute = get(id).dispute();
            return approvals.submit(
                    admin,
                    TrustGatedActions.DISPUTE_REFUND,
                    "dispute",
                    id.toString(),
                    Map.of(
                            "id",
                            id,
                            "amountPaise",
                            amount.value(),
                            "flagHost",
                            Boolean.TRUE.equals(request.flagHost())),
                    List.of(new Approvals.ApprovalChange("refund to user " + dispute.renterId(), 0, amount.value())),
                    request.reason());
        }
        resolved(disputes.refund(
                id, amount, request.reason(), Boolean.TRUE.equals(request.flagHost()), audit.admin(admin)));
        return ResponseEntity.ok(get(id));
    }

    /** Resolves the report without a refund. The same again changes nothing. */
    @RequiresPermission(value = AdminPermission.DISPUTES_DECIDE, record = "dispute")
    @PostMapping("/{id}/no-refund")
    AdminDispute noRefund(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminNoRefund request) {
        resolved(disputes.noRefund(id, request.reason(), Boolean.TRUE.equals(request.flagHost()), audit.admin(admin)));
        return get(id);
    }

    private static void resolved(Disputes.Resolved outcome) {
        switch (outcome) {
            case Disputes.Resolved.Done _, Disputes.Resolved.AlreadyDone _ -> {
                // Shown as it is now.
            }
            case Disputes.Resolved.NotFound _ -> throw notFound();
            case Disputes.Resolved.Refused refused -> throw ApiProblem.of(Problem.ACTION_REFUSED, refused.why());
        }
    }

    private static ApiProblem notFound() {
        return ApiProblem.of(Problem.NOT_FOUND, "There's no such report.");
    }

    private static AdminDisputeSummary summary(ResultSet rs, int row) throws SQLException {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new AdminDisputeSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("renter_id", UUID.class),
                rs.getString("reason"),
                rs.getString("details"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("resolution_reason"),
                rs.getString("resolved_by"),
                resolvedAt == null ? null : resolvedAt.toInstant(),
                rs.getObject("refund_paise", Long.class),
                rs.getObject("commission_refund_paise", Long.class),
                rs.getObject("host_refund_paise", Long.class),
                rs.getObject("refund_txn", UUID.class),
                rs.getLong("version"));
    }
}
