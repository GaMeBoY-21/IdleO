package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.SessionCharges;
import com.idleo.controlplane.sessions.MeteredSessions;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles a session's billable minutes (technical design "The settlement transaction", ADR 0017). One settlement,
 * in one transaction under the session's row lock: claim the billable, unbilled, unwaived minutes before the
 * window's end, charge them as one ledger transaction, and move the hold. Its {@code txn_id} is a name-based UUID
 * of the session and the window's number, so a retry after a crash, which rolled everything back, settles the same
 * window under the same id, and a window already settled claims nothing.
 *
 * <p>A minute is claimed only while the session can pay for it: never past its spending cap, and never past its
 * hold plus the renter's available balance. Minutes beyond either are left unbilled and go to review.
 */
@Component
class Settlements {

    private static final Logger log = LoggerFactory.getLogger(Settlements.class);

    /** A hold covers this many minutes ahead (technical design "Metering and billing" step 1). */
    static final int HOLD_MINUTES = CreditReservations.HOLD_MINUTES;

    private final MeteredSessions sessions;
    private final SessionCharges charges;
    private final CreditReservations reservations;
    private final JdbcClient jdbc;

    Settlements(MeteredSessions sessions, SessionCharges charges, CreditReservations reservations, JdbcClient jdbc) {
        this.sessions = sessions;
        this.charges = charges;
        this.reservations = reservations;
        this.jdbc = jdbc;
    }

    /** The metered session, as a settlement needs it. */
    record Metered(UUID sessionId, UUID renterId, long maxSpendPaise, int settlements) {}

    /** A billable minute: the attempt that ran it decides its price and host. */
    private record Minute(int index, Instant minuteAt, int attempt) {}

    /** The ledger transaction of a session's n-th settlement. */
    static UUID txnId(UUID sessionId, int windowNumber) {
        return UUID.nameUUIDFromBytes(
                ("session-settlement:" + sessionId + ":" + windowNumber).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Settles the session's billable minutes before {@code windowEnd} (all of them when null). Returns what it
     * charged, zero if there was nothing to charge. The caller holds the session's row lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Paise settle(Metered session, @Nullable Instant windowEnd, Instant now) {
        var attempts = new HashMap<Integer, MeteredSessions.Attempt>();
        for (var attempt : sessions.attempts(session.sessionId())) {
            attempts.put(attempt.attempt(), attempt);
        }
        var limit = limit(session);
        var claimed = new ArrayList<Minute>();
        var parts = new LinkedHashMap<UUID, Paise>();
        var minutesByHost = new java.util.HashMap<UUID, Integer>();
        var total = Paise.ZERO;
        for (var minute : billable(session.sessionId(), windowEnd)) {
            var attempt = attempts.get(minute.attempt());
            if (attempt == null) {
                continue;
            }
            var price = Paise.of(attempt.pricePaisePerMin());
            if (total.plus(price).isGreaterThan(limit)) {
                break;
            }
            total = total.plus(price);
            parts.merge(attempt.hostId(), price, Paise::plus);
            minutesByHost.merge(attempt.hostId(), 1, Integer::sum);
            claimed.add(minute);
        }
        if (claimed.isEmpty()) {
            return Paise.ZERO;
        }
        var charged = post(session, attempts, claimed, parts, minutesByHost, windowEnd, now, true);
        log.info(
                "Settled session {} window {}: {} minutes, {} paise ({} to hosts, {} commission)",
                session.sessionId(),
                session.settlements() + 1,
                claimed.size(),
                charged.amount().value(),
                charged.hostShare().value(),
                charged.commission().value());
        return charged.amount();
    }

    /**
     * Charges one minute from the review queue that an admin decided was worked (ADR 0027), as a settlement of its
     * own: the session's next window number, one ledger transaction, at the price of the attempt that ran it. Refused,
     * charging nothing, past the session's spending cap or what the renter's credit can pay. The caller holds the
     * session's row lock; the minute isn't marked billed, since one witness never saw it.
     *
     * @return the settlement's ledger transaction
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = NotChargeable.class)
    UUID settleReviewed(Metered session, int minuteIndex, Instant minuteAt, int attempt, Instant now) {
        var attempts = new HashMap<Integer, MeteredSessions.Attempt>();
        for (var known : sessions.attempts(session.sessionId())) {
            attempts.put(known.attempt(), known);
        }
        var ran = java.util.Optional.ofNullable(attempts.get(attempt))
                .orElseThrow(() -> new NotChargeable(
                        "attempt " + attempt + " of the session isn't known, so there's no price or host to charge"));
        var price = Paise.of(ran.pricePaisePerMin());
        var limit = limit(session);
        if (price.isGreaterThan(limit)) {
            throw new NotChargeable("the minute costs " + price.value() + " paise, but the session's spending cap and"
                    + " the renter's credit leave only " + limit.value() + " paise");
        }
        post(
                session,
                attempts,
                List.of(new Minute(minuteIndex, minuteAt, attempt)),
                Map.of(ran.hostId(), price),
                Map.of(ran.hostId(), 1),
                null,
                now,
                false);
        return txnId(session.sessionId(), session.settlements() + 1);
    }

    /** The reviewed minute can't be charged; nothing changed. */
    static final class NotChargeable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotChargeable(String message) {
            super(message);
        }
    }

    /**
     * Posts a settlement of {@code claimed}: marks them billed if {@code markBilled}, charges them as one ledger
     * transaction, and records it and each host's part, as the session's next window.
     */
    private SessionCharges.Charged post(
            Metered session,
            Map<Integer, MeteredSessions.Attempt> attempts,
            List<Minute> claimed,
            Map<UUID, Paise> parts,
            Map<UUID, Integer> minutesByHost,
            @Nullable Instant windowEnd,
            Instant now,
            boolean markBilled) {
        int number = session.settlements() + 1;
        var txnId = txnId(session.sessionId(), number);
        if (markBilled) {
            for (var minute : claimed) {
                jdbc.sql("""
                                update metering.usage_minutes set billed = true, settlement_txn = :txnId
                                where session_id = :sessionId and minute_index = :index and minute_at = :minuteAt
                                  and not billed and not waived
                                """)
                        .param("txnId", txnId)
                        .param("sessionId", session.sessionId())
                        .param("index", minute.index())
                        .param("minuteAt", Timestamp.from(minute.minuteAt()))
                        .update();
            }
        }
        var total = parts.values().stream().reduce(Paise.ZERO, Paise::plus);
        var chargedBefore = chargedNet(session.sessionId());
        var charged = charges.settle(new SessionCharges.Charge(
                session.sessionId(),
                session.renterId(),
                txnId,
                parts.entrySet().stream()
                        .map(part -> new SessionCharges.Part(part.getKey(), part.getValue()))
                        .toList(),
                holdTarget(session, attempts, chargedBefore.plus(total))));
        jdbc.sql("""
                        insert into metering.settlements
                            (txn_id, session_id, window_number, window_end, minutes, amount_paise, host_share_paise,
                             commission_paise, settled_at)
                        values (:txnId, :sessionId, :number, :windowEnd, :minutes, :amount, :hostShare, :commission, :now)
                        """)
                .param("txnId", txnId)
                .param("sessionId", session.sessionId())
                .param("number", number)
                .param("windowEnd", windowEnd == null ? null : Timestamp.from(windowEnd))
                .param("minutes", claimed.size())
                .param("amount", charged.amount().value())
                .param("hostShare", charged.hostShare().value())
                .param("commission", charged.commission().value())
                .param("now", Timestamp.from(now))
                .update();
        for (var host : charged.hosts()) {
            jdbc.sql("""
                            insert into metering.settlement_parts
                                (txn_id, host_id, amount_paise, host_share_paise, minutes)
                            values (:txnId, :hostId, :amount, :share, :minutes)
                            """)
                    .param("txnId", txnId)
                    .param("hostId", host.hostId())
                    .param("amount", host.amount().value())
                    .param("share", host.share().value())
                    .param("minutes", minutesByHost.get(host.hostId()))
                    .update();
        }
        jdbc.sql("update metering.metered_sessions set settlements = :number where session_id = :sessionId")
                .param("number", number)
                .param("sessionId", session.sessionId())
                .update();
        return charged;
    }

    /** The most more this session may be charged: its cap's remainder, and what its hold and balance can pay. */
    Paise limit(Metered session) {
        var capLeft = Paise.of(session.maxSpendPaise()).minus(chargedNet(session.sessionId()));
        var payable = reservations
                .held(session.sessionId())
                .orElse(Paise.ZERO)
                .plus(reservations.available(session.renterId()));
        var limit = capLeft.isGreaterThan(payable) ? payable : capLeft;
        return limit.isNegative() ? Paise.ZERO : limit;
    }

    /** 30 minutes at the current attempt's price, but never past the spending cap. */
    private Paise holdTarget(Metered session, Map<Integer, MeteredSessions.Attempt> attempts, Paise chargedAfter) {
        var current = attempts.values().stream()
                .max(java.util.Comparator.comparingInt(MeteredSessions.Attempt::attempt))
                .orElseThrow();
        var ahead = Paise.of(current.pricePaisePerMin()).times(HOLD_MINUTES);
        var capLeft = Paise.of(session.maxSpendPaise()).minus(chargedAfter);
        var target = ahead.isGreaterThan(capLeft) ? capLeft : ahead;
        return target.isNegative() ? Paise.ZERO : target;
    }

    /** What the session has been charged so far, less what lost minutes gave back. */
    Paise chargedNet(UUID sessionId) {
        return Paise.of(
                jdbc.sql("""
                        select (select coalesce(sum(amount_paise), 0) from metering.settlements
                                where session_id = :sessionId)
                             - (select coalesce(sum(refunded_paise), 0) from metering.waivers
                                where session_id = :sessionId)
                        """).param("sessionId", sessionId).query(Long.class).single());
    }

    /**
     * Billable, unbilled, unwaived minutes before {@code windowEnd}, oldest first; a lost attempt's minutes after its
     * checkpoint count as waived even before they're marked.
     */
    private List<Minute> billable(UUID sessionId, @Nullable Instant windowEnd) {
        return jdbc.sql("""
                        select u.minute_index, u.minute_at, u.agent_attempt from metering.usage_minutes u
                        where u.session_id = :sessionId and u.agent_reported and u.lease_alive
                          and u.agent_attempt = u.lease_attempt and not u.billed and not u.waived
                          and (cast(:windowEnd as timestamptz) is null or u.minute_at < cast(:windowEnd as timestamptz))
                          and not exists (select 1 from metering.waivers w where w.session_id = u.session_id and %s)
                        order by u.minute_index
                        """.formatted(Waivers.WAIVED))
                .param("sessionId", sessionId)
                .param("windowEnd", windowEnd == null ? null : Timestamp.from(windowEnd))
                .query((rs, n) -> new Minute(
                        rs.getInt("minute_index"),
                        rs.getTimestamp("minute_at").toInstant(),
                        rs.getInt("agent_attempt")))
                .list();
    }
}
