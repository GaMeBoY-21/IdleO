package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Rupees;
import com.idleo.controlplane.billing.PayoutHolds;
import com.idleo.controlplane.billing.SessionRefunds;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.trust.ReportResolved;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Renters' reports of a problem with a session, and how an admin resolves them (ADR 0028). The rules, in one place:
 *
 * <ul>
 *   <li>a renter reports their own ended session, within 7 days of it ending, with one of six reasons; a session has
 *       one report, ever, so a resolution can't be followed by another report and another payout hold; while it's
 *       open the session's host earnings are held from payout;
 *   <li>an admin resolves a report once: with a refund of up to what the renter paid for the session, net of
 *       everything given back before, or without one; either way with a reason the renter reads;
 *   <li>a refund is one balanced ledger transaction through billing, from the platform's commission first and then
 *       the hosts' shares, under a {@code txn_id} derived from the report; the resolution, the refund, the end of the
 *       hold and the audit row commit together.
 * </ul>
 */
@Service
class Disputes {

    /** A session can be reported this long after it ended. */
    static final Duration WINDOW = Duration.ofDays(7);

    static final int MAX_DETAILS = 2000;

    /** Why a renter reports a session. */
    static final List<String> REASONS =
            List.of("didnt_start", "failed", "slow", "host_stopped", "wrong_output", "charged_wrong", "other");

    static final String OPEN = "open";
    static final String REFUNDED = "refunded";
    static final String NOT_REFUNDED = "not_refunded";

    /** A report, as stored. Its refund's split is set only when it was resolved with one. */
    record Dispute(
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

    /** What happened when a renter reported a session. */
    sealed interface Opened {
        record Created(Dispute dispute) implements Opened {}

        /** Not their session, or it hasn't ended. */
        record NotFound() implements Opened {}

        record WindowClosed(Instant closedAt) implements Opened {}

        record AlreadyOpen(UUID disputeId) implements Opened {}

        /** The session's one report was already made, and resolved. */
        record AlreadyMade(UUID disputeId) implements Opened {}
    }

    /** What happened when an admin resolved a report. */
    sealed interface Resolved {
        record Done(Dispute dispute) implements Resolved {}

        /** It was already resolved this same way; nothing changed. */
        record AlreadyDone(Dispute dispute) implements Resolved {}

        record NotFound() implements Resolved {}

        record Refused(String why) implements Resolved {}
    }

    private final EndedSessions sessions;
    private final SessionRefunds refunds;
    private final PayoutHolds holds;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    Disputes(
            EndedSessions sessions,
            SessionRefunds refunds,
            PayoutHolds holds,
            AuditTrail audit,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            Clock clock,
            ApplicationEventPublisher events) {
        this.events = events;
        this.sessions = sessions;
        this.refunds = refunds;
        this.holds = holds;
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
    }

    /** The ledger transaction of a report's refund: one a report, so resolving it again can never pay twice. */
    static UUID refundTxnId(UUID disputeId) {
        return UUID.nameUUIDFromBytes(("dispute-refund:" + disputeId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Opens a report on the renter's ended session, and holds its host earnings from payout. Joins the caller's
     * transaction, the one its Idempotency-Key is recorded in.
     */
    @Transactional
    Opened open(UUID renterId, UUID sessionId, String reason, @Nullable String details) {
        if (!REASONS.contains(reason)) {
            throw new IllegalArgumentException("unknown reason " + reason);
        }
        var text = details == null || details.isBlank() ? null : details.strip();
        if (text != null && text.length() > MAX_DETAILS) {
            throw new IllegalArgumentException("details are at most " + MAX_DETAILS + " characters");
        }
        var session = sessions.lockOfRenter(renterId, sessionId).orElse(null);
        if (session == null) {
            return new Opened.NotFound();
        }
        var now = now();
        var closesAt = session.endedAt().plus(WINDOW);
        if (!now.isBefore(closesAt)) {
            return new Opened.WindowClosed(closesAt);
        }
        var earlier = jdbc.sql("select id, state from trust.disputes where session_id = :sessionId")
                .param("sessionId", sessionId)
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("state")))
                .optional();
        if (earlier.isPresent()) {
            var made = earlier.get();
            return made.getValue().equals(OPEN)
                    ? new Opened.AlreadyOpen(made.getKey())
                    : new Opened.AlreadyMade(made.getKey());
        }
        var id = ids.newId();
        jdbc.sql("""
                        insert into trust.disputes (id, session_id, renter_id, reason, details, state, created_at)
                        values (:id, :sessionId, :renterId, :reason, :details, 'open', :now)
                        """)
                .param("id", id)
                .param("sessionId", sessionId)
                .param("renterId", renterId)
                .param("reason", reason)
                .param("details", text)
                .param("now", Timestamp.from(now))
                .update();
        if (session.hostId() != null) {
            holds.hold(id, sessionId);
        }
        return new Opened.Created(find(id).orElseThrow());
    }

    Optional<Dispute> find(UUID disputeId) {
        return jdbc.sql("select * from trust.disputes where id = :id")
                .param("id", disputeId)
                .query(Disputes::dispute)
                .optional();
    }

    /** The renter's reports, newest first, after {@code after}; {@code sessionId} keeps one session's. */
    List<Dispute> ofRenter(UUID renterId, @Nullable UUID sessionId, @Nullable UUID after, int limit) {
        return jdbc.sql("""
                        select * from trust.disputes
                        where renter_id = :renterId
                          and (cast(:sessionId as uuid) is null or session_id = cast(:sessionId as uuid))
                          and (cast(:after as uuid) is null or id < cast(:after as uuid))
                        order by id desc
                        limit :limit
                        """)
                .param("renterId", renterId)
                .param("sessionId", sessionId)
                .param("after", after)
                .param("limit", limit)
                .query(Disputes::dispute)
                .list();
    }

    /**
     * Resolves the report with a refund of {@code amount}, which the renter reads with {@code reason}; {@code flagHost}
     * marks the session's host's record too.
     */
    Resolved refund(UUID disputeId, Paise amount, String reason, boolean flagHost, AuditTrail.Actor actor) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a refund is more than zero");
        }
        return resolve(disputeId, amount, reason, flagHost, actor);
    }

    /** Resolves the report without a refund; the renter reads {@code reason}. */
    Resolved noRefund(UUID disputeId, String reason, boolean flagHost, AuditTrail.Actor actor) {
        return resolve(disputeId, null, reason, flagHost, actor);
    }

    private Resolved resolve(
            UUID disputeId, @Nullable Paise amount, String reason, boolean flagHost, AuditTrail.Actor actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a resolution has a reason");
        }
        var state = amount == null ? NOT_REFUNDED : REFUNDED;
        return Objects.requireNonNull(transactions.execute(status -> {
            var dispute = jdbc.sql("select * from trust.disputes where id = :id for update")
                    .param("id", disputeId)
                    .query(Disputes::dispute)
                    .optional()
                    .orElse(null);
            if (dispute == null) {
                return new Resolved.NotFound();
            }
            if (!dispute.state().equals(OPEN)) {
                boolean same = dispute.state().equals(state)
                        && (amount == null || Objects.equals(dispute.refundPaise(), amount.value()));
                return same
                        ? new Resolved.AlreadyDone(dispute)
                        : new Resolved.Refused("This report was already resolved: " + described(dispute) + ".");
            }
            var hostId = sessions.find(dispute.sessionId())
                    .map(EndedSessions.EndedSession::hostId)
                    .orElse(null);
            if (flagHost && hostId == null) {
                return new Resolved.Refused("The session never ran on a host, so there's no host to flag.");
            }
            SessionRefunds.Refund refund = null;
            if (amount != null) {
                var paid = refunds.paid(dispute.sessionId()).renterPaid();
                if (amount.isGreaterThan(paid)) {
                    return new Resolved.Refused("A refund can be at most what the session cost, net of anything "
                            + "given back before: " + Rupees.format(paid.value()) + ".");
                }
                refund = refunds.refund(dispute.sessionId(), dispute.renterId(), refundTxnId(disputeId), amount);
            }
            var now = now();
            var split = refund == null ? null : refund.split();
            jdbc.sql("""
                            update trust.disputes
                            set state = :state, resolution_reason = :reason, resolved_by = :by, resolved_at = :now,
                                refund_paise = :refund, commission_refund_paise = :commission,
                                host_refund_paise = :host, refund_txn = :txn
                            where id = :id
                            """)
                    .param("state", state)
                    .param("reason", reason.strip())
                    .param("by", actor.label())
                    .param("now", Timestamp.from(now))
                    .param("refund", split == null ? null : split.amount().value())
                    .param(
                            "commission",
                            split == null ? null : split.fromCommission().value())
                    .param("host", split == null ? null : split.fromHostsTotal().value())
                    .param("txn", refund == null ? null : refund.txnId())
                    .param("id", disputeId)
                    .update();
            holds.release(disputeId);
            if (flagHost) {
                jdbc.sql("""
                                insert into trust.host_flags (id, host_id, dispute_id, reason, flagged_by, created_at)
                                values (:id, :hostId, :disputeId, :reason, :by, :now)
                                """)
                        .param("id", ids.newId())
                        .param("hostId", hostId)
                        .param("disputeId", disputeId)
                        .param("reason", reason.strip())
                        .param("by", actor.label())
                        .param("now", Timestamp.from(now))
                        .update();
            }
            events.publishEvent(new ReportResolved(
                    disputeId,
                    dispute.sessionId(),
                    dispute.renterId(),
                    split == null ? null : split.amount().value(),
                    reason.strip()));
            var resolved = find(disputeId).orElseThrow();
            var after = new LinkedHashMap<String, Object>();
            after.put("state", state);
            if (flagHost) {
                after.put("flaggedHost", Objects.requireNonNull(hostId).toString());
            }
            if (refund != null) {
                after.put("refundPaise", split.amount().value());
                after.put("fromCommissionPaise", split.fromCommission().value());
                after.put("fromHostsPaise", split.fromHostsTotal().value());
                after.put(
                        "fromHosts",
                        split.fromHosts().stream()
                                .map(host -> Map.of(
                                        "hostId",
                                        host.hostId().toString(),
                                        "paise",
                                        host.amount().value()))
                                .toList());
                after.put("refundTxn", refund.txnId().toString());
            }
            audit.record(new AuditTrail.Entry(
                    actor,
                    refund == null ? "dispute.no_refund" : "dispute.refund",
                    "dispute",
                    disputeId.toString(),
                    reason,
                    Map.of("state", OPEN, "sessionId", dispute.sessionId().toString()),
                    after));
            return new Resolved.Done(resolved);
        }));
    }

    /**
     * The session a report is about, for an admin to read its log (ADR 0039); {@code audited}: the first page, which
     * writes the audit row naming the admin.
     */
    Optional<UUID> sessionForLog(UUID disputeId, boolean audited, AuditTrail.Actor actor) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var sessionId = jdbc.sql("select session_id from trust.disputes where id = :id")
                    .param("id", disputeId)
                    .query(UUID.class)
                    .optional();
            if (audited && sessionId.isPresent()) {
                audit.record(new AuditTrail.Entry(
                        actor,
                        "dispute.log_read",
                        "dispute",
                        disputeId.toString(),
                        "read the session's log to judge the report",
                        null,
                        Map.of("sessionId", sessionId.get().toString())));
            }
            return sessionId;
        }));
    }

    private static String described(Dispute dispute) {
        return dispute.state().equals(REFUNDED)
                ? "refunded " + Rupees.format(Objects.requireNonNull(dispute.refundPaise()))
                : "without a refund";
    }

    private static Dispute dispute(ResultSet rs, int row) throws SQLException {
        return new Dispute(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("renter_id", UUID.class),
                rs.getString("reason"),
                rs.getString("details"),
                rs.getString("state"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("resolution_reason"),
                rs.getString("resolved_by"),
                rs.getTimestamp("resolved_at") == null
                        ? null
                        : rs.getTimestamp("resolved_at").toInstant(),
                rs.getObject("refund_paise", Long.class),
                rs.getObject("commission_refund_paise", Long.class),
                rs.getObject("host_refund_paise", Long.class),
                rs.getObject("refund_txn", UUID.class));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
