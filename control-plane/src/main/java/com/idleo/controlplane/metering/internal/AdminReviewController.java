package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.sessions.MeteredSessions;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.HashMap;
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
 * The review queue, for the admin console (ADR 0027): each minute only one witness saw, or that the spending cap or
 * the renter's credit couldn't pay for, charged or waived with a reason. Oldest first, so the queue is worked in
 * order.
 */
@RestController
@RequestMapping("/admin/v1/review-minutes")
@ConditionalOnWebApplication
class AdminReviewController {

    private final ReviewQueue queue;
    private final MeteredSessions sessions;
    private final JdbcClient jdbc;
    private final AuditTrail audit;

    AdminReviewController(ReviewQueue queue, MeteredSessions sessions, JdbcClient jdbc, AuditTrail audit) {
        this.queue = queue;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * One minute in the queue: its session and renter, which attempt ran it on which host at what price, why it's
     * here ({@code agent_only}, {@code lease_only} or {@code over_limit}), and how it was decided, if it was.
     */
    record AdminReviewMinute(
            UUID sessionId,
            int minuteIndex,
            Instant minuteAt,
            UUID renterId,
            @Nullable Integer attempt,
            @Nullable UUID hostId,
            @Nullable Integer pricePaisePerMin,
            String reason,
            Instant queuedAt,
            @Nullable String resolution,
            @Nullable String resolutionReason,
            @Nullable String resolvedBy,
            @Nullable Instant resolvedAt,
            @Nullable UUID settlementTxn,
            long version) {}

    record AdminReviewPage(
            List<AdminReviewMinute> minutes, @Nullable String nextCursor) {}

    /** The minutes in review, their filters, orders and CSV (ADR 0052). The oldest is the next to decide. */
    static final AdminListing LIST = AdminListing.of("review-minutes", AdminPermission.REVIEW_READ, true)
            .select("r.*, m.renter_id")
            .from("metering.review_minutes r join metering.metered_sessions m on m.session_id = r.session_id")
            .id("r.session_id::text || ':' || lpad(r.minute_index::text, 10, '0')", AdminListing.Type.TEXT)
            .sort("queuedAt", AdminListing.Type.TIME, "r.created_at")
            .sort("minuteAt", AdminListing.Type.TIME, "r.minute_at")
            .sort("reason", AdminListing.Type.TEXT, "r.reason")
            .sortedBy("queuedAt", false)
            .filter("state", """
                    (cast(:state as text) = 'open' and r.resolution is null)
                    or (cast(:state as text) = 'decided' and r.resolution is not null)""")
            .filter("session", "r.session_id = cast(:session as uuid)")
            .csv("sessionId", "r.session_id")
            .csv("minuteIndex", "r.minute_index")
            .csv("minuteAt", "r.minute_at")
            .csv("renterId", "m.renter_id")
            .csv("attempt", "r.attempt")
            .csv("reason", "r.reason")
            .csv("queuedAt", "r.created_at")
            .csv("resolution", "r.resolution")
            .csv("resolutionReason", "r.resolution_reason")
            .csv("resolvedBy", "r.resolved_by")
            .csv("resolvedAt", "r.resolved_at")
            .build();

    /** {@code state} is {@code open} (the default), {@code decided} or {@code all}; {@code session} keeps one session's. */
    @RequiresPermission(AdminPermission.REVIEW_READ)
    @AdminList("review-minutes")
    @GetMapping
    AdminReviewPage list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable UUID session,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var which = state == null || state.isBlank() ? "open" : state.strip();
        if (!List.of("open", "decided", "all").contains(which)) {
            throw ApiProblem.invalid("state", "must be open, decided or all.");
        }
        var attempts = new HashMap<UUID, List<MeteredSessions.Attempt>>();
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(sort, "state", which.equals("all") ? null : which, "session", session),
                cursor,
                limit,
                (rs, n) -> {
                    var sessionId = rs.getObject("session_id", UUID.class);
                    var attempt = rs.getObject("attempt", Integer.class);
                    var ran = attempt == null
                            ? null
                            : attempts.computeIfAbsent(sessionId, sessions::attempts).stream()
                                    .filter(each -> each.attempt() == attempt)
                                    .findFirst()
                                    .orElse(null);
                    return new AdminReviewMinute(
                            sessionId,
                            rs.getInt("minute_index"),
                            rs.getTimestamp("minute_at").toInstant(),
                            rs.getObject("renter_id", UUID.class),
                            attempt,
                            ran == null ? null : ran.hostId(),
                            ran == null ? null : ran.pricePaisePerMin(),
                            rs.getString("reason"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("resolution"),
                            rs.getString("resolution_reason"),
                            rs.getString("resolved_by"),
                            rs.getTimestamp("resolved_at") == null
                                    ? null
                                    : rs.getTimestamp("resolved_at").toInstant(),
                            rs.getObject("settlement_txn", UUID.class),
                            rs.getLong("version"));
                });
        return new AdminReviewPage(page.rows(), page.nextCursor());
    }

    /** Charges the minute at its attempt's price, as a settlement of its own, within the cap and the credit. */
    @RequiresPermission(value = AdminPermission.REVIEW_CHARGE, record = "review_minute")
    @PostMapping("/{sessionId}/{minuteIndex}/charge")
    AdminReviewDecision charge(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID sessionId,
            @PathVariable int minuteIndex,
            @Valid @RequestBody AdminReason request) {
        return decision(queue.charge(sessionId, minuteIndex, request.reason(), audit.admin(admin)));
    }

    /** Waives the minute: it's never charged, and no money moves. */
    @RequiresPermission(value = AdminPermission.REVIEW_WAIVE, record = "review_minute")
    @PostMapping("/{sessionId}/{minuteIndex}/waive")
    AdminReviewDecision waive(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID sessionId,
            @PathVariable int minuteIndex,
            @Valid @RequestBody AdminReason request) {
        return decision(queue.waive(sessionId, minuteIndex, request.reason(), audit.admin(admin)));
    }

    /** How the minute stands now: {@code charged} (with its settlement's ledger transaction) or {@code waived}. */
    record AdminReviewDecision(String resolution, @Nullable UUID settlementTxn) {}

    private AdminReviewDecision decision(ReviewQueue.Outcome outcome) {
        return switch (outcome) {
            case ReviewQueue.Outcome.Decided decided ->
                new AdminReviewDecision(decided.resolution(), decided.settlementTxn());
            case ReviewQueue.Outcome.AlreadyDecided already -> new AdminReviewDecision(already.resolution(), null);
            case ReviewQueue.Outcome.NotFound _ ->
                throw ApiProblem.of(Problem.NOT_FOUND, "There's no such minute in the review queue.");
            case ReviewQueue.Outcome.Refused refused -> throw ApiProblem.of(Problem.ACTION_REFUSED, refused.why());
        };
    }
}
