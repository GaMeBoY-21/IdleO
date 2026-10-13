package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The review queue (ADR 0017, ADR 0027): minutes only one witness saw, or that the spending cap or the renter's
 * credit couldn't pay for, are never charged by themselves. An admin decides each, once, with a reason: charged, as
 * a settlement of its own through the same ledger path as every other (within the cap and the renter's credit), or
 * waived, moving no money. Each decision is audited in its own transaction, under the session's row lock, so no
 * settlement runs beside it.
 */
@Service
class ReviewQueue {

    private final Settlements settlements;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;
    private final Clock clock;

    ReviewQueue(
            Settlements settlements,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            AuditTrail audit,
            Clock clock) {
        this.settlements = settlements;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = audit;
        this.clock = clock;
    }

    /** What happened: decided now, decided already the same way (nothing changed), or refused. */
    sealed interface Outcome {
        record Decided(String resolution, @Nullable UUID settlementTxn) implements Outcome {}

        record AlreadyDecided(String resolution) implements Outcome {}

        record NotFound() implements Outcome {}

        record Refused(String why) implements Outcome {}
    }

    Outcome charge(UUID sessionId, int minuteIndex, String reason, AuditTrail.Actor actor) {
        return decide(sessionId, minuteIndex, "charged", reason, actor);
    }

    Outcome waive(UUID sessionId, int minuteIndex, String reason, AuditTrail.Actor actor) {
        return decide(sessionId, minuteIndex, "waived", reason, actor);
    }

    private record Item(
            Instant minuteAt,
            @Nullable Integer attempt,
            String reason,
            @Nullable String resolution) {}

    private Outcome decide(UUID sessionId, int minuteIndex, String resolution, String reason, AuditTrail.Actor actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a review decision has a reason");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            var metered = jdbc.sql("""
                            select renter_id, max_spend_paise, settlements from metering.metered_sessions
                            where session_id = :sessionId for update
                            """)
                    .param("sessionId", sessionId)
                    .query((rs, n) -> new Settlements.Metered(
                            sessionId,
                            rs.getObject("renter_id", UUID.class),
                            rs.getLong("max_spend_paise"),
                            rs.getInt("settlements")))
                    .optional()
                    .orElse(null);
            var item = jdbc.sql("""
                            select minute_at, attempt, reason, resolution from metering.review_minutes
                            where session_id = :sessionId and minute_index = :index for update
                            """)
                    .param("sessionId", sessionId)
                    .param("index", minuteIndex)
                    .query((rs, n) -> new Item(
                            rs.getTimestamp("minute_at").toInstant(),
                            rs.getObject("attempt", Integer.class),
                            rs.getString("reason"),
                            rs.getString("resolution")))
                    .optional()
                    .orElse(null);
            if (metered == null || item == null) {
                return new Outcome.NotFound();
            }
            if (item.resolution() != null) {
                return item.resolution().equals(resolution)
                        ? new Outcome.AlreadyDecided(resolution)
                        : new Outcome.Refused("This minute was already " + item.resolution() + ".");
            }
            UUID txnId = null;
            var now = now();
            if (resolution.equals("charged")) {
                if (item.attempt() == null) {
                    return new Outcome.Refused("No attempt ran this minute, so there's no price or host to charge.");
                }
                if (givenBack(sessionId, minuteIndex)) {
                    return new Outcome.Refused(
                            "This minute was given back with a lost host's work, so it can't be charged.");
                }
                try {
                    txnId = settlements.settleReviewed(metered, minuteIndex, item.minuteAt(), item.attempt(), now);
                } catch (Settlements.NotChargeable e) {
                    return new Outcome.Refused("It can't be charged: " + e.getMessage() + ".");
                }
            }
            jdbc.sql("""
                            update metering.review_minutes
                            set resolution = :resolution, resolution_reason = :reason, resolved_by = :by,
                                resolved_at = :now, settlement_txn = :txnId
                            where session_id = :sessionId and minute_index = :index
                            """)
                    .param("resolution", resolution)
                    .param("reason", reason.strip())
                    .param("by", actor.label())
                    .param("now", Timestamp.from(now))
                    .param("txnId", txnId)
                    .param("sessionId", sessionId)
                    .param("index", minuteIndex)
                    .update();
            var after = new LinkedHashMap<String, Object>();
            after.put("resolution", resolution);
            after.put("settlementTxn", txnId == null ? null : txnId.toString());
            audit.record(new AuditTrail.Entry(
                    actor,
                    resolution.equals("charged") ? "review_minute.charge" : "review_minute.waive",
                    "review_minute",
                    sessionId + ":" + minuteIndex,
                    reason,
                    Map.of("queuedFor", item.reason()),
                    after));
            return new Outcome.Decided(resolution, txnId);
        }));
    }

    /** Whether a lost attempt's waiver covers the minute: its work was given back, so it's never charged. */
    private boolean givenBack(UUID sessionId, int minuteIndex) {
        return jdbc.sql("""
                        select exists (
                            select 1 from metering.usage_minutes u
                            where u.session_id = :sessionId and u.minute_index = :index
                              and (u.waived or exists (select 1 from metering.waivers w
                                                       where w.session_id = u.session_id and %s)))
                        """.formatted(Waivers.WAIVED))
                .param("sessionId", sessionId)
                .param("index", minuteIndex)
                .query(Boolean.class)
                .single();
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
