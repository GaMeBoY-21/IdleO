package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.sessions.MeteredSessions;
import com.idleo.controlplane.sessions.SessionControl;
import com.idleo.controlplane.sessions.SessionMilestones;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps credit ahead of a running session (technical design "Metering and billing" step 5, ADR 0017). On each
 * tick it counts what the session has committed: minutes both witnesses saw and nobody has settled, plus the
 * current attempt's minutes that have started but aren't decided yet. Billing gets that number, so lease renewal
 * can tell whether the hold, less it, still covers a lease.
 *
 * <ul>
 *   <li>When the hold runs low and more credit exists, it settles early: settlement tops the hold back up.
 *   <li>When the hold can't cover the next lease (the minutes it would let the attempt start, beyond its current
 *       one), it stops the session with a checkpoint first: {@code
 *       max_spend_reached} if the spending cap is what limits the hold, {@code credit_exhausted} otherwise.
 *   <li>When the hold plus the renter's available balance covers under 10 minutes, the session is {@code
 *       low_credit}.
 * </ul>
 */
@Component
class CreditGuard {

    private static final Logger log = LoggerFactory.getLogger(CreditGuard.class);
    private static final int LOW_CREDIT_MINUTES = 10;

    private final MeteredSessions sessions;
    private final SessionControl control;
    private final CreditReservations reservations;
    private final Settlements settlements;
    private final Costs costs;
    private final SessionMilestones milestones;
    private final JdbcClient jdbc;

    CreditGuard(
            MeteredSessions sessions,
            SessionControl control,
            CreditReservations reservations,
            Settlements settlements,
            Costs costs,
            SessionMilestones milestones,
            JdbcClient jdbc) {
        this.sessions = sessions;
        this.control = control;
        this.reservations = reservations;
        this.settlements = settlements;
        this.costs = costs;
        this.milestones = milestones;
        this.jdbc = jdbc;
    }

    /** One check of a running session, under its row lock; {@code metered} is read just before. */
    @Transactional(propagation = Propagation.MANDATORY)
    void check(Settlements.Metered metered, Instant now, java.util.function.Supplier<Settlements.Metered> reread) {
        var sessionId = metered.sessionId();
        var session = sessions.session(sessionId).orElse(null);
        if (session == null || !session.live() || stoppedFor(sessionId)) {
            return;
        }
        var current = sessions.attempts(sessionId).stream()
                .filter(attempt -> attempt.attempt() == session.attempt())
                .findFirst()
                .orElse(null);
        if (current == null || current.runningAt() == null || current.endedAt() != null) {
            return;
        }
        var price = Paise.of(current.pricePaisePerMin());
        var leaseCost = price.times(MeteredSessions.minutesALeaseStarts(current.runningAt(), now, sessions.leaseTtl()));
        var committed = committed(sessionId, current, price, now);
        var hold = reservations.held(sessionId).orElse(Paise.ZERO);
        var available = reservations.available(metered.renterId());
        var capLeft = Paise.of(metered.maxSpendPaise()).minus(settlements.chargedNet(sessionId));
        boolean capLimited = !capLeft.isGreaterThan(hold);
        if (hold.minus(committed).compareTo(leaseCost.plus(price)) < 0 && !capLimited && available.isPositive()) {
            settlements.settle(metered, now, now);
            committed = committed(sessionId, current, price, now);
            hold = reservations.held(sessionId).orElse(Paise.ZERO);
            available = reservations.available(metered.renterId());
            capLeft = Paise.of(reread.get().maxSpendPaise()).minus(settlements.chargedNet(sessionId));
            capLimited = !capLeft.isGreaterThan(hold);
        }
        reservations.markCommitted(sessionId, committed);
        if (hold.minus(committed).compareTo(leaseCost) < 0) {
            var reason = capLimited
                    ? SessionControl.CreditStop.MAX_SPEND_REACHED
                    : SessionControl.CreditStop.CREDIT_EXHAUSTED;
            if (control.stopForCredit(sessionId, reason)) {
                jdbc.sql("update metering.metered_sessions set stopped_for = :reason where session_id = :sessionId")
                        .param(
                                "reason",
                                reason == SessionControl.CreditStop.MAX_SPEND_REACHED
                                        ? "max_spend_reached"
                                        : "credit_exhausted")
                        .param("sessionId", sessionId)
                        .update();
                log.info(
                        "Stopping session {} for {}: hold {} paise, committed {} paise",
                        sessionId,
                        reason,
                        hold.value(),
                        committed.value());
            }
        }
        boolean low = hold.plus(available).minus(committed).compareTo(price.times(LOW_CREDIT_MINUTES)) < 0;
        int flipped =
                jdbc.sql("""
                        update metering.metered_sessions set low_credit = :low
                        where session_id = :sessionId and low_credit <> :low
                        """).param("low", low).param("sessionId", sessionId).update();
        if (low && flipped == 1) {
            milestones.lowOnCredit(sessionId);
        }
    }

    /** Billable minutes not settled, plus the current attempt's started minutes not decided yet. */
    private Paise committed(UUID sessionId, MeteredSessions.Attempt current, Paise price, Instant now) {
        long started = Duration.between(current.runningAt(), now).toMinutes() + 1;
        long checkedThrough = jdbc.sql("""
                        select coalesce((select checked_through from metering.metered_attempts
                                         where session_id = :sessionId and attempt = :attempt), -1)
                        """)
                .param("sessionId", sessionId)
                .param("attempt", current.attempt())
                .query(Long.class)
                .single();
        long undecided = Math.max(0, started - (checkedThrough + 1));
        return Paise.of(costs.accruedPaise(sessionId)).plus(price.times(undecided));
    }

    private boolean stoppedFor(UUID sessionId) {
        return jdbc.sql("select stopped_for is not null from metering.metered_sessions where session_id = :sessionId")
                .param("sessionId", sessionId)
                .query(Boolean.class)
                .single();
    }
}
