package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.billing.HostEarnings;
import com.idleo.controlplane.hosts.HostWork;
import com.idleo.controlplane.sessions.SessionCosts;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What the sessions module tells hosts about the work on a machine (ADR 0023), never who it's for. Money comes from
 * the ledger (billing's {@link HostEarnings}) and from what metering has accrued but not settled.
 */
@Service
class HostWorkService implements HostWork {

    private final JdbcClient jdbc;
    private final TemplateCatalog catalog;
    private final HostEarnings earnings;
    private final ObjectProvider<SessionCosts> costs;

    HostWorkService(
            JdbcClient jdbc, TemplateCatalog catalog, HostEarnings earnings, ObjectProvider<SessionCosts> costs) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.earnings = earnings;
        this.costs = costs;
    }

    @Override
    public boolean isBusy(UUID hostId) {
        return jdbc.sql("""
                        select exists (
                            select 1 from sessions.sessions
                            where host_id = :hostId and state in ('MATCHED', 'PROVISIONING', 'RUNNING', 'FINISHING'))
                        """).param("hostId", hostId).query(Boolean.class).single();
    }

    private record Running(UUID hostId, UUID sessionId, String templateId, java.time.Instant startedAt) {}

    /**
     * A host's session counts as current once it runs, from its attempt's first RUNNING until it ends; for the few
     * seconds it's matched or starting, the host is busy but has nothing to show yet.
     */
    @Override
    public Map<UUID, CurrentSession> current(Collection<UUID> hostIds) {
        if (hostIds.isEmpty()) {
            return Map.of();
        }
        var running = jdbc.sql("""
                        select s.host_id, s.id, s.template_id, a.running_at
                        from sessions.sessions s
                        join sessions.attempts a on a.session_id = s.id and a.attempt = s.attempt
                        where s.host_id in (:hostIds) and s.state in ('RUNNING', 'FINISHING')
                            and a.running_at is not null
                        """)
                .param("hostIds", hostIds)
                .query((rs, n) -> new Running(
                        rs.getObject("host_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getString("template_id"),
                        rs.getTimestamp("running_at").toInstant()))
                .list();
        var costs = this.costs.getIfAvailable();
        var current = new HashMap<UUID, CurrentSession>();
        for (var session : running) {
            long settled = earnings.bySession(session.hostId(), java.util.List.of(session.sessionId()))
                    .getOrDefault(session.sessionId(), HostEarnings.Earned.NONE)
                    .netPaise();
            long accrued = costs == null ? 0 : costs.of(session.sessionId()).accruedPaise();
            var service = catalog.serviceOf(session.templateId());
            current.put(
                    session.hostId(),
                    new CurrentSession(
                            service.map(com.idleo.controlplane.sessions.Service::name)
                                    .orElse(session.templateId()),
                            service.map(com.idleo.controlplane.sessions.Service::runningLabel)
                                    .orElse("Running"),
                            session.startedAt(),
                            settled + earnings.shareOf(accrued)));
        }
        return current;
    }
}
