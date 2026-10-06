package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.SessionCharges;
import com.idleo.controlplane.sessions.MeteredSessions;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Minutes lost with a host are given back (ADR 0017). When an attempt loses its host, the work after its newest
 * complete checkpoint is gone, and the next attempt redoes it: every minute of the lost attempt that ends after
 * that checkpoint was written (after its first RUNNING, if it had none) is waived. A waived minute is never
 * settled; one settled before the loss was known is refunded, once per attempt, in one reversing transaction
 * whose {@code txn_id} is a name-based UUID of the session and attempt: the renter is credited, and each host's
 * payable and the platform's revenue debited in the proportions of the settlement that charged it.
 */
@Component
class Waivers {

    private static final Logger log = LoggerFactory.getLogger(Waivers.class);

    /** A waived minute: a whole minute's work, any of which was lost, is given back whole. */
    static final String WAIVED = """
            coalesce(u.agent_attempt, u.lease_attempt) = w.attempt
            and u.minute_at + interval '1 minute' > w.waived_from""";

    private final MeteredSessions sessions;
    private final SessionCharges charges;
    private final JdbcClient jdbc;

    Waivers(MeteredSessions sessions, SessionCharges charges, JdbcClient jdbc) {
        this.sessions = sessions;
        this.charges = charges;
        this.jdbc = jdbc;
    }

    /** The reversing transaction of a lost attempt's settled minutes. */
    static UUID txnId(UUID sessionId, int attempt) {
        return UUID.nameUUIDFromBytes(("session-waiver:" + sessionId + ":" + attempt).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Refunds {@code refund} of a host's settlement part, in that part's proportions: the host gives back its
     * share of it, rounded down, and the platform the rest. Refunding the whole part gives back exactly what
     * each was credited.
     */
    static SessionCharges.RefundPart refundPart(SessionCharges.HostShare part, Paise refund) {
        if (refund.isGreaterThan(part.amount())) {
            throw new IllegalArgumentException("a refund never exceeds what was charged");
        }
        var host = Paise.of(Math.multiplyExact(refund.value(), part.share().value())
                / part.amount().value());
        return new SessionCharges.RefundPart(part.hostId(), host, refund.minus(host));
    }

    /**
     * Waives the lost attempt's minutes after {@code checkpointAt} and refunds those already settled. Doing it
     * again changes nothing. Takes the metered session's row lock, so no settlement runs meanwhile.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void waive(UUID sessionId, int attempt, @Nullable Instant checkpointAt, Instant now) {
        var renterId = jdbc.sql("""
                        select renter_id from metering.metered_sessions where session_id = :sessionId for update
                        """)
                .param("sessionId", sessionId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        var lost = sessions.attempts(sessionId).stream()
                .filter(candidate -> candidate.attempt() == attempt)
                .findFirst()
                .orElse(null);
        if (renterId == null || lost == null || lost.runningAt() == null) {
            return;
        }
        var waivedFrom =
                checkpointAt != null && checkpointAt.isAfter(lost.runningAt()) ? checkpointAt : lost.runningAt();
        var txnId = txnId(sessionId, attempt);
        int recorded = jdbc.sql("""
                        insert into metering.waivers
                            (session_id, attempt, host_id, checkpoint_at, waived_from, refund_txn, refunded_minutes,
                             refunded_paise, host_refund_paise, platform_refund_paise, created_at)
                        values (:sessionId, :attempt, :hostId, :checkpointAt, :waivedFrom, :txnId, 0, 0, 0, 0, :now)
                        on conflict do nothing
                        """)
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .param("hostId", lost.hostId())
                .param("checkpointAt", checkpointAt == null ? null : Timestamp.from(checkpointAt))
                .param("waivedFrom", Timestamp.from(waivedFrom))
                .param("txnId", txnId)
                .param("now", Timestamp.from(now))
                .update();
        if (recorded == 0) {
            return;
        }
        int waived = jdbc.sql("""
                        update metering.usage_minutes u set waived = true
                        from metering.waivers w
                        where w.session_id = :sessionId and w.attempt = :attempt and u.session_id = w.session_id
                          and not u.waived and %s
                        """.formatted(WAIVED))
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .update();
        var price = Paise.of(lost.pricePaisePerMin());
        var parts = new ArrayList<SessionCharges.RefundPart>();
        int refundedMinutes = 0;
        var settled = jdbc.sql("""
                        select p.host_id, p.amount_paise, p.host_share_paise, count(*) as minutes
                        from metering.usage_minutes u
                        join metering.waivers w on w.session_id = u.session_id and w.attempt = :attempt
                        join metering.settlement_parts p on p.txn_id = u.settlement_txn and p.host_id = :hostId
                        where u.session_id = :sessionId and u.billed and u.waived and u.refund_txn is null and %s
                        group by u.settlement_txn, p.host_id, p.amount_paise, p.host_share_paise
                        """.formatted(WAIVED))
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .param("hostId", lost.hostId())
                .query((rs, n) -> new Object[] {
                    new SessionCharges.HostShare(
                            rs.getObject("host_id", UUID.class),
                            Paise.of(rs.getLong("amount_paise")),
                            Paise.of(rs.getLong("host_share_paise"))),
                    rs.getInt("minutes")
                })
                .list();
        for (var row : settled) {
            int minutes = (Integer) row[1];
            parts.add(refundPart((SessionCharges.HostShare) row[0], price.times(minutes)));
            refundedMinutes += minutes;
        }
        var host = parts.stream().map(SessionCharges.RefundPart::host).reduce(Paise.ZERO, Paise::plus);
        var platform = parts.stream().map(SessionCharges.RefundPart::platform).reduce(Paise.ZERO, Paise::plus);
        if (refundedMinutes > 0) {
            charges.refund(sessionId, renterId, txnId, parts);
            jdbc.sql("""
                            update metering.usage_minutes u set refund_txn = :txnId
                            from metering.waivers w
                            where w.session_id = :sessionId and w.attempt = :attempt and u.session_id = w.session_id
                              and u.billed and u.waived and u.refund_txn is null and %s
                            """.formatted(WAIVED))
                    .param("txnId", txnId)
                    .param("sessionId", sessionId)
                    .param("attempt", attempt)
                    .update();
            jdbc.sql("""
                            update metering.waivers
                            set refunded_minutes = :minutes, refunded_paise = :refunded, host_refund_paise = :host,
                                platform_refund_paise = :platform
                            where session_id = :sessionId and attempt = :attempt
                            """)
                    .param("minutes", refundedMinutes)
                    .param("refunded", host.plus(platform).value())
                    .param("host", host.value())
                    .param("platform", platform.value())
                    .param("sessionId", sessionId)
                    .param("attempt", attempt)
                    .update();
        }
        log.info(
                "Waived session {} attempt {}'s minutes after {}: {} minutes, {} of them settled and refunded ({} paise)",
                sessionId,
                attempt,
                waivedFrom,
                waived,
                refundedMinutes,
                host.plus(platform).value());
    }

    /**
     * Waives minutes of a lost attempt that arrived after its waiver, for example usage the agent resent when it
     * reconnected. They were never settled: settlement skips them too.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void waiveLateMinutes(UUID sessionId) {
        jdbc.sql("""
                        update metering.usage_minutes u set waived = true
                        from metering.waivers w
                        where w.session_id = :sessionId and u.session_id = w.session_id
                          and not u.waived and not u.billed and %s
                        """.formatted(WAIVED)).param("sessionId", sessionId).update();
    }
}
