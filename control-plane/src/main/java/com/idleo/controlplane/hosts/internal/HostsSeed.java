package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ConsoleSeed;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Hosts' made-up machines (ADR 0052), most of them active. */
@Component
class HostsSeed implements ConsoleSeed {

    /** How far apart hosts are. */
    static final long EVERY_MS = 3_600_000L;

    private final JdbcClient jdbc;

    HostsSeed(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void seed(Sizes sizes) {
        jdbc.sql("""
                        insert into hosts.hosts (id, owner_user_id, name, status, created_at, updated_at, version)
                        select %s, %s,
                               (array['Studio PC','Gaming rig','Laptop','Workstation','Render box'])[1 + n %% 5] || ' ' || n,
                               case when n %% 20 = 0 then 'suspended' else 'active' end,
                               %s, %s, 1
                        from generate_series(0, :hosts - 1) n
                        on conflict do nothing
                        """.formatted(
                                ConsoleSeed.id("host", "n", EVERY_MS),
                                ConsoleSeed.userId("n % :users"),
                                ConsoleSeed.at("n", EVERY_MS),
                                ConsoleSeed.at("n", EVERY_MS)))
                .param("hosts", sizes.hosts())
                .param("users", sizes.users())
                .update();
    }
}
