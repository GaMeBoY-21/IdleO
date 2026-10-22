package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
 * The review of software no renter may run on a host's GPU, for the admin console (ADR 0038): the queue (open first, oldest
 * first), one item with every signal on it, what the job did each minute and what the session cost, and the three things an
 * admin does: confirm it (the suspension stays, and the account may be banned), clear it (the account is reinstated and the
 * session's charge refunded) or add a note. Each is audited with the admin's typed reason.
 */
@RestController
@RequestMapping("/admin/v1/abuse")
@ConditionalOnWebApplication
class AdminAbuseController {

    /**
     * An item, as the queue lists it.
     *
     * @param action what happened without an admin: {@code none}, {@code stopped} (the session) or {@code suspended} (the
     *     session, and its renter's account while the item was open)
     * @param kinds the kinds of signal on it, in the order they came
     * @param resolution the admin's reason when it was settled
     */
    record AbuseSummary(
            UUID id,
            UUID sessionId,
            int attempt,
            UUID renterId,
            @Nullable UUID hostId,

            @Schema(allowableValues = {"open", "confirmed", "cleared"})
            String state,

            @Schema(allowableValues = {"none", "stopped", "suspended"})
            String action,

            boolean banned,
            List<String> kinds,
            Instant createdAt,
            @Nullable Instant resolvedAt,
            @Nullable String resolution) {}

    record AbusePage(List<AbuseSummary> items, @Nullable String nextCursor) {}

    /** One signal: what the runner or the behaviour reports showed, and whether the runner stopped the session for it. */
    record AbuseSignalView(
            @Schema(
                    allowableValues = {
                        "process_name",
                        "process_hash",
                        "pool_lookup",
                        "connection_drops",
                        "mining_pattern"
                    })
            String kind,

            String detail,
            String sha256,
            long count,
            boolean stopped,
            Instant at) {}

    record AbuseNoteView(UUID authorId, String note, Instant at) {}

    /** What the job did in one minute. */
    record BehaviourMinute(
            int minuteIndex,
            int gpuPercent,
            int memoryPercent,
            int pcieKbPerSecond,
            long networkOutBytes,
            long networkInBytes,
            long printedLines,
            long connectionsDropped) {}

    /**
     * An item with its evidence. {@code paidPaise} is what the renter paid for the session, net of what was given back, and
     * so what clearing refunds; {@code refundedPaise} is what clearing did refund.
     */
    record AbuseDetail(
            AbuseSummary item,
            List<AbuseSignalView> signals,
            List<AbuseNoteView> notes,
            List<BehaviourMinute> behaviour,
            long paidPaise,
            @Nullable Long refundedPaise) {}

    /** Confirming: {@code ban} is true to ban the account, which only clearing the item lifts. */
    record AbuseConfirm(
            boolean ban,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {

        AbuseConfirm {
            reason = reason == null ? null : reason.strip();
        }
    }

    /** A note, which is its own reason. */
    record AbuseNote(
            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String note) {

        AbuseNote {
            note = note == null ? null : note.strip();
        }
    }

    private final AbuseReviews reviews;
    private final AuditTrail audit;
    private final JdbcClient jdbc;

    AdminAbuseController(AbuseReviews reviews, AuditTrail audit, JdbcClient jdbc) {
        this.reviews = reviews;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /** {@code state} is {@code open} (the default), {@code confirmed}, {@code cleared} or {@code all}; oldest first. */
    @GetMapping
    AbusePage list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        var which = state == null || state.isBlank() ? "open" : state.strip();
        if (!List.of("open", "confirmed", "cleared", "all").contains(which)) {
            throw ApiProblem.invalid("state", "must be open, confirmed, cleared or all.");
        }
        var after = AdminPages.after(cursor);
        var rows = jdbc.sql("""
                        select i.*, coalesce((select array_agg(s.kind order by s.at, s.id) from trust.abuse_signals s
                                              where s.item_id = i.id), '{}') kinds
                        from trust.abuse_items i
                        where (:which = 'all' or i.state = :which)
                          and (cast(:after as uuid) is null or i.id > cast(:after as uuid))
                        order by i.id
                        limit :limit
                        """)
                .param("which", which)
                .param("after", after)
                .param("limit", size + 1)
                .query(AdminAbuseController::summary)
                .list();
        var page = rows.size() > size ? rows.subList(0, size) : rows;
        return new AbusePage(
                List.copyOf(page),
                rows.size() > size ? AdminPages.cursorAfter(page.getLast().id()) : null);
    }

    @GetMapping("/{id}")
    AbuseDetail get(@PathVariable UUID id) {
        var item = reviews.find(id).orElseThrow(AdminAbuseController::notFound);
        var summary = jdbc.sql("""
                        select i.*, coalesce((select array_agg(s.kind order by s.at, s.id) from trust.abuse_signals s
                                              where s.item_id = i.id), '{}') kinds
                        from trust.abuse_items i where i.id = :id
                        """)
                .param("id", id)
                .query(AdminAbuseController::summary)
                .single();
        var behaviour = jdbc.sql("""
                        select * from (
                            select * from trust.behavior_samples where session_id = :session
                            order by attempt desc, minute_index desc limit 60) recent
                        order by attempt, minute_index
                        """)
                .param("session", item.sessionId())
                .query((rs, n) -> new BehaviourMinute(
                        rs.getInt("minute_index"),
                        rs.getInt("util_pct"),
                        rs.getInt("memory_pct"),
                        rs.getInt("pcie_kb_s"),
                        rs.getLong("net_out"),
                        rs.getLong("net_in"),
                        rs.getLong("printed"),
                        rs.getLong("conn_drops")))
                .list();
        return new AbuseDetail(
                summary,
                reviews.signals(id).stream()
                        .map(signal -> new AbuseSignalView(
                                signal.kind(),
                                signal.detail(),
                                signal.sha256(),
                                signal.count(),
                                signal.stopped(),
                                signal.at()))
                        .toList(),
                reviews.notes(id).stream()
                        .map(note -> new AbuseNoteView(note.authorId(), note.note(), note.at()))
                        .toList(),
                behaviour,
                reviews.paid(item.sessionId()).value(),
                item.refundPaise());
    }

    /**
     * Confirms an open item: the suspension stays, and the account is banned if asked (a ban is lifted only by clearing the
     * item). 409 {@code action-refused} for an item already cleared, or an account that can't be banned.
     */
    @PostMapping("/{id}/confirm")
    AbuseDetail confirm(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AbuseConfirm request) {
        settled(reviews.confirm(id, request.ban(), request.reason(), audit.admin(admin)));
        return get(id);
    }

    /**
     * Clears an item: the account is reinstated and everything the session cost its renter is refunded, one ledger
     * transaction. The same again changes nothing.
     */
    @PostMapping("/{id}/clear")
    AbuseDetail clear(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        settled(reviews.clear(id, request.reason(), audit.admin(admin)));
        return get(id);
    }

    /** Adds a note to the item. */
    @PostMapping("/{id}/notes")
    AbuseDetail note(
            @AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id, @Valid @RequestBody AbuseNote request) {
        settled(reviews.note(id, request.note(), audit.admin(admin)));
        return get(id);
    }

    private static void settled(AbuseReviews.Result result) {
        switch (result) {
            case AbuseReviews.Result.Done _ -> {
                // Shown as it is now.
            }
            case AbuseReviews.Result.NotFound _ -> throw notFound();
            case AbuseReviews.Result.Refused refused -> throw ApiProblem.of(Problem.ACTION_REFUSED, refused.why());
        }
    }

    private static ApiProblem notFound() {
        return ApiProblem.of(Problem.NOT_FOUND, "There's no such review.");
    }

    private static AbuseSummary summary(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var item = AbuseReviews.item(rs, row);
        var kinds = List.of((String[]) rs.getArray("kinds").getArray());
        return new AbuseSummary(
                item.id(),
                item.sessionId(),
                item.attempt(),
                item.renterId(),
                item.hostId(),
                item.state(),
                item.action(),
                item.banned(),
                kinds,
                item.createdAt(),
                item.resolvedAt(),
                item.resolution());
    }
}
