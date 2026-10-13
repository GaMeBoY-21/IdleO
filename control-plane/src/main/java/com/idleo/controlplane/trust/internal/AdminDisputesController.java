package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.billing.SessionRefunds;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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

    private final Disputes disputes;
    private final EndedSessions sessions;
    private final Ratings ratings;
    private final SessionRefunds refunds;
    private final AuditTrail audit;
    private final JdbcClient jdbc;

    AdminDisputesController(
            Disputes disputes,
            EndedSessions sessions,
            Ratings ratings,
            SessionRefunds refunds,
            AuditTrail audit,
            JdbcClient jdbc) {
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
            @Nullable UUID refundTxn) {}

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

    record AdminDispute(
            AdminDisputeSummary dispute,
            AdminDisputeSession session,
            @Nullable AdminDisputeRating rating) {}

    /**
     * @param amountPaise refunded to the renter's credit: more than zero, at most what the session cost net
     * @param reason what the renter reads, 5 to 500 characters; it's the audit row's reason too
     */
    record AdminRefund(
            @NotNull @Min(1) Long amountPaise,
            @NotBlank @Size(min = 5, max = 500) String reason) {

        AdminRefund {
            reason = reason == null ? null : reason.strip();
        }
    }

    /** {@code state} is {@code open}, {@code resolved} or {@code all} (the default). */
    @GetMapping
    AdminDisputePage list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable UUID session,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        var which = state == null || state.isBlank() ? "all" : state.strip();
        if (!List.of("open", "resolved", "all").contains(which)) {
            throw ApiProblem.invalid("state", "must be open, resolved or all.");
        }
        var after = Position.decode(cursor);
        var rows = jdbc.sql("""
                        select * from trust.disputes
                        where (:which = 'all' or (state = 'open') = (:which = 'open'))
                          and (cast(:session as uuid) is null or session_id = cast(:session as uuid))
                          and (cast(:afterId as uuid) is null
                               or ((state <> 'open')::int, id) > (:afterRank, cast(:afterId as uuid)))
                        order by (state <> 'open')::int, id
                        limit :limit
                        """)
                .param("which", which)
                .param("session", session)
                .param("afterRank", after == null ? 0 : after.rank())
                .param("afterId", after == null ? null : after.id())
                .param("limit", size + 1)
                .query(AdminDisputesController::summary)
                .list();
        var page = rows.size() > size ? rows.subList(0, size) : rows;
        var last = page.isEmpty() ? null : page.getLast();
        return new AdminDisputePage(
                List.copyOf(page),
                rows.size() > size && last != null
                        ? new Position(last.state().equals(Disputes.OPEN) ? 0 : 1, last.id()).encode()
                        : null);
    }

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
                rated == null ? null : new AdminDisputeRating(rated.stars(), rated.comment(), rated.ratedAt()));
    }

    /**
     * Resolves the report with a refund to the renter's credit, from the platform's commission first and then the
     * hosts' shares. 409 {@code action-refused} for more than the session cost net, or a report already resolved
     * another way; the same refund again changes nothing.
     */
    @PostMapping("/{id}/refund")
    AdminDispute refund(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminRefund request) {
        resolved(disputes.refund(id, Paise.of(request.amountPaise()), request.reason(), audit.admin(admin)));
        return get(id);
    }

    /** Resolves the report without a refund. The same again changes nothing. */
    @PostMapping("/{id}/no-refund")
    AdminDispute noRefund(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody com.idleo.controlplane.AdminReason request) {
        resolved(disputes.noRefund(id, request.reason(), audit.admin(admin)));
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
                rs.getObject("refund_txn", UUID.class));
    }

    /** Where a page ends in the queue's order: open (0) or resolved (1), then the report's id. */
    private record Position(int rank, UUID id) {

        String encode() {
            var raw = "q:" + rank + ":" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static @Nullable Position decode(@Nullable String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                var parts =
                        new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8).split(":");
                if (parts.length == 3 && parts[0].equals("q") && (parts[1].equals("0") || parts[1].equals("1"))) {
                    return new Position(Integer.parseInt(parts[1]), UUID.fromString(parts[2]));
                }
            } catch (IllegalArgumentException e) {
                // Refused below.
            }
            throw ApiProblem.invalid("cursor", "isn't a cursor this API gave out.");
        }
    }
}
