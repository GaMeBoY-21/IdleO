package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.billing.PayoutHolds;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Holds on a session's host earnings while a report about it is open (ADR 0028), in {@code billing.payout_holds}. */
@Service
class PayoutHoldService implements PayoutHolds {

    private final JdbcClient jdbc;
    private final Clock clock;

    PayoutHoldService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void hold(UUID disputeId, UUID sessionId) {
        jdbc.sql("""
                        insert into billing.payout_holds (dispute_id, session_id, held_at)
                        values (:disputeId, :sessionId, :now)
                        on conflict (dispute_id) do nothing
                        """)
                .param("disputeId", disputeId)
                .param("sessionId", sessionId)
                .param("now", Timestamp.from(now()))
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID disputeId) {
        jdbc.sql("""
                        update billing.payout_holds set released_at = :now
                        where dispute_id = :disputeId and released_at is null
                        """)
                .param("now", Timestamp.from(now()))
                .param("disputeId", disputeId)
                .update();
    }

    /** The sessions among these whose host earnings are held now. */
    Set<UUID> held(Collection<UUID> sessionIds) {
        var held = new HashSet<UUID>();
        if (sessionIds.isEmpty()) {
            return held;
        }
        jdbc.sql("""
                        select distinct session_id from billing.payout_holds
                        where session_id in (:sessionIds) and released_at is null
                        """).param("sessionIds", sessionIds).query(rs -> {
            held.add(rs.getObject("session_id", UUID.class));
        });
        return held;
    }

    /** The sessions held now that any of these machines earned from (ADR 0040: their earnings wait for the report). */
    Set<UUID> heldAmong(Collection<UUID> hostIds) {
        var held = new HashSet<UUID>();
        if (hostIds.isEmpty()) {
            return held;
        }
        jdbc.sql("""
                        select distinct h.session_id from billing.payout_holds h
                        where h.released_at is null
                          and exists (select 1 from billing.ledger_entries e
                                      where e.ref_id = h.session_id and e.account = 'host_payable'
                                        and e.owner_id in (:hostIds))
                        """).param("hostIds", hostIds).query(rs -> {
            held.add(rs.getObject("session_id", UUID.class));
        });
        return held;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
