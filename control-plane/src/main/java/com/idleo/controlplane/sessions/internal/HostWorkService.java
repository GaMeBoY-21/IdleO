package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.hosts.HostWork;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** What the sessions module tells hosts about the work on a machine (ADR 0023). */
@Service
class HostWorkService implements HostWork {

    private final JdbcClient jdbc;

    HostWorkService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isBusy(UUID hostId) {
        return jdbc.sql("""
                        select exists (
                            select 1 from sessions.sessions
                            where host_id = :hostId and state in ('MATCHED', 'PROVISIONING', 'RUNNING', 'FINISHING'))
                        """).param("hostId", hostId).query(Boolean.class).single();
    }
}
