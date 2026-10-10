package com.idleo.controlplane.metering.internal;

import com.idleo.controlplane.billing.EarningDetails;
import com.idleo.controlplane.sessions.MeteredSessions;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Labels a host's ledger entries with the service and minutes behind them (ADR 0023). */
@Service
class EarningDetailService implements EarningDetails {

    private record Raw(UUID txnId, UUID hostId, UUID sessionId, int minutes) {}

    private final JdbcClient jdbc;
    private final MeteredSessions sessions;

    EarningDetailService(JdbcClient jdbc, MeteredSessions sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Override
    public Map<Key, Detail> of(Collection<Key> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        var txns = keys.stream().map(Key::txnId).distinct().toList();
        var raw = new java.util.ArrayList<Raw>();
        raw.addAll(jdbc.sql("""
                        select p.txn_id, p.host_id, s.session_id, p.minutes
                        from metering.settlement_parts p
                        join metering.settlements s on s.txn_id = p.txn_id
                        where p.txn_id in (:txns)
                        """)
                .param("txns", txns)
                .query((rs, n) -> new Raw(
                        rs.getObject("txn_id", UUID.class),
                        rs.getObject("host_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getInt("minutes")))
                .list());
        raw.addAll(jdbc.sql("""
                        select refund_txn, host_id, session_id, refunded_minutes from metering.waivers
                        where refund_txn in (:txns)
                        """)
                .param("txns", txns)
                .query((rs, n) -> new Raw(
                        rs.getObject("refund_txn", UUID.class),
                        rs.getObject("host_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getInt("refunded_minutes")))
                .list());
        var names = sessions.serviceNames(
                raw.stream().map(Raw::sessionId).distinct().toList());
        var wanted = new java.util.HashSet<>(keys);
        var details = new HashMap<Key, Detail>();
        for (var row : raw) {
            var key = new Key(row.txnId(), row.hostId());
            if (wanted.contains(key)) {
                details.put(key, new Detail(names.get(row.sessionId()), row.minutes()));
            }
        }
        return details;
    }
}
