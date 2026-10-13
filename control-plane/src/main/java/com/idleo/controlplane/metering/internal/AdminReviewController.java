package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.sessions.MeteredSessions;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
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
            @Nullable UUID settlementTxn) {}

    record AdminReviewPage(
            List<AdminReviewMinute> minutes, @Nullable String nextCursor) {}

    /** {@code state} is {@code open} (the default), {@code decided} or {@code all}; {@code session} keeps one session's. */
    @GetMapping
    AdminReviewPage list(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable UUID session,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        var which = state == null || state.isBlank() ? "open" : state.strip();
        if (!List.of("open", "decided", "all").contains(which)) {
            throw ApiProblem.invalid("state", "must be open, decided or all.");
        }
        var after = Position.decode(cursor);
        record Row(
                UUID sessionId,
                int minuteIndex,
                Instant minuteAt,
                UUID renterId,
                @Nullable Integer attempt,
                String reason,
                Instant queuedAt,
                @Nullable String resolution,
                @Nullable String resolutionReason,
                @Nullable String resolvedBy,
                @Nullable Instant resolvedAt,
                @Nullable UUID settlementTxn) {}
        var rows = jdbc.sql("""
                        select r.*, m.renter_id from metering.review_minutes r
                        join metering.metered_sessions m on m.session_id = r.session_id
                        where (:which = 'all' or (r.resolution is null) = (:which = 'open'))
                          and (cast(:session as uuid) is null or r.session_id = cast(:session as uuid))
                          and (cast(:afterAt as timestamptz) is null
                               or (r.created_at, r.session_id, r.minute_index)
                                  > (cast(:afterAt as timestamptz), cast(:afterSession as uuid), :afterIndex))
                        order by r.created_at, r.session_id, r.minute_index
                        limit :limit
                        """)
                .param("which", which)
                .param("session", session)
                .param("afterAt", after == null ? null : Timestamp.from(after.queuedAt()))
                .param("afterSession", after == null ? null : after.sessionId())
                .param("afterIndex", after == null ? 0 : after.minuteIndex())
                .param("limit", size + 1)
                .query((rs, n) -> new Row(
                        rs.getObject("session_id", UUID.class),
                        rs.getInt("minute_index"),
                        rs.getTimestamp("minute_at").toInstant(),
                        rs.getObject("renter_id", UUID.class),
                        rs.getObject("attempt", Integer.class),
                        rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("resolution"),
                        rs.getString("resolution_reason"),
                        rs.getString("resolved_by"),
                        rs.getTimestamp("resolved_at") == null
                                ? null
                                : rs.getTimestamp("resolved_at").toInstant(),
                        rs.getObject("settlement_txn", UUID.class)))
                .list();
        var page = rows.size() > size ? rows.subList(0, size) : rows;
        var attempts = new HashMap<UUID, List<MeteredSessions.Attempt>>();
        var minutes = page.stream()
                .map(row -> {
                    var ran = row.attempt() == null
                            ? null
                            : attempts.computeIfAbsent(row.sessionId(), sessions::attempts).stream()
                                    .filter(attempt -> attempt.attempt() == row.attempt())
                                    .findFirst()
                                    .orElse(null);
                    return new AdminReviewMinute(
                            row.sessionId(),
                            row.minuteIndex(),
                            row.minuteAt(),
                            row.renterId(),
                            row.attempt(),
                            ran == null ? null : ran.hostId(),
                            ran == null ? null : ran.pricePaisePerMin(),
                            row.reason(),
                            row.queuedAt(),
                            row.resolution(),
                            row.resolutionReason(),
                            row.resolvedBy(),
                            row.resolvedAt(),
                            row.settlementTxn());
                })
                .toList();
        var last = page.isEmpty() ? null : page.getLast();
        return new AdminReviewPage(
                minutes,
                rows.size() > size && last != null
                        ? new Position(last.queuedAt(), last.sessionId(), last.minuteIndex()).encode()
                        : null);
    }

    /** Charges the minute at its attempt's price, as a settlement of its own, within the cap and the credit. */
    @PostMapping("/{sessionId}/{minuteIndex}/charge")
    AdminReviewDecision charge(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID sessionId,
            @PathVariable int minuteIndex,
            @Valid @RequestBody AdminReason request) {
        return decision(queue.charge(sessionId, minuteIndex, request.reason(), audit.admin(admin)));
    }

    /** Waives the minute: it's never charged, and no money moves. */
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

    /** Where a page ends in the queue's order: when the minute was queued, its session and its index. */
    private record Position(Instant queuedAt, UUID sessionId, int minuteIndex) {

        String encode() {
            var raw = "r:" + queuedAt.getEpochSecond() + "." + queuedAt.getNano() + ":" + sessionId + ":" + minuteIndex;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static @Nullable Position decode(@Nullable String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                var parts =
                        new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8).split(":");
                if (parts.length == 4 && parts[0].equals("r")) {
                    var time = parts[1].split("\\.");
                    return new Position(
                            Instant.ofEpochSecond(Long.parseLong(time[0]), Long.parseLong(time[1])),
                            UUID.fromString(parts[2]),
                            Integer.parseInt(parts[3]));
                }
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
                // Refused below.
            }
            throw ApiProblem.invalid("cursor", "isn't a cursor this API gave out.");
        }
    }
}
