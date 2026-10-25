package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.hosts.HostTrustSignals;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What lowers a host's score that trust knows (hosts' {@link HostTrustSignals}): the reports an admin answered with a
 * refund, and the admin's flag on the host (ADR 0028, ADR 0039). Neither says who reported or what they wrote.
 */
@Component
class TrustSignals implements HostTrustSignals {

    private final JdbcClient jdbc;

    TrustSignals(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Signal> since(Collection<UUID> hostIds, Instant since) {
        if (hostIds.isEmpty()) {
            return List.of();
        }
        var signals = new java.util.ArrayList<Signal>();
        jdbc.sql("""
                        select s.host_id, d.resolved_at as at
                        from trust.disputes d join trust.ended_sessions s on s.session_id = d.session_id
                        where d.state = 'refunded' and s.host_id in (:hostIds) and d.resolved_at >= :since
                        """)
                .param("hostIds", hostIds)
                .param("since", Timestamp.from(since))
                .query(rs -> {
                    signals.add(new Signal(
                            rs.getObject("host_id", UUID.class),
                            rs.getTimestamp("at").toInstant(),
                            Kind.UPHELD_REPORT));
                });
        jdbc.sql("""
                        select host_id, created_at as at from trust.host_flags
                        where host_id in (:hostIds) and created_at >= :since
                        """)
                .param("hostIds", hostIds)
                .param("since", Timestamp.from(since))
                .query(rs -> {
                    signals.add(new Signal(
                            rs.getObject("host_id", UUID.class),
                            rs.getTimestamp("at").toInstant(),
                            Kind.FLAG));
                });
        return signals;
    }
}
