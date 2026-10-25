package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.hosts.HostWork;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the hosts module holds about a machine's owner (ADR 0037, docs/reference/personal-data-map.md): their machines,
 * GPUs, prices, history and the links that added them. Erasure removes each machine as "Remove" does, so its agent
 * hears it and the laptop's app says so; the rows stay, because the ledger points at them, with nothing that names
 * the person or the machine.
 */
@Component
class HostsPersonalData implements PersonalData {

    static final String ERASED = "the owner's account was deleted";

    private final JdbcClient jdbc;
    private final ExportRows rows;
    private final HostLifecycle lifecycle;
    private final HostWork work;

    HostsPersonalData(JdbcClient jdbc, ExportRows rows, HostLifecycle lifecycle, HostWork work) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.lifecycle = lifecycle;
        this.work = work;
    }

    @Override
    public String module() {
        return "hosts";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of(jdbc.sql("select count(*), min(created_at), max(created_at) from hosts.hosts"
                        + " where owner_user_id = :user")
                .param("user", userId)
                .query((rs, n) -> new Group(
                        "hosting", "machines", rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .single());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public List<Part> export(UUID userId) {
        var params = Map.of("user", userId);
        return List.of(
                new Part("hosting", "machines", rows.list(jdbc, """
                                select id, name, status, os, agent_version, created_at, last_seen_at, paused_at,
                                       paused_until, keep_free_gb, removed_at
                                from hosts.hosts where owner_user_id = :user order by created_at
                                """, params)),
                new Part("hosting", "gpus", rows.list(jdbc, """
                                select g.host_id, g.gpu_uuid, g.model, g.vram_mb, g.driver_version, g.first_seen_at,
                                       g.last_reported_at, g.gone_at
                                from hosts.host_gpus g join hosts.hosts h on h.id = g.host_id
                                where h.owner_user_id = :user order by g.first_seen_at
                                """, params)),
                new Part("hosting", "prices", rows.list(jdbc, """
                                select p.host_id, p.workload_type, p.price_paise_per_min, p.updated_at
                                from hosts.price_listings p join hosts.hosts h on h.id = p.host_id
                                where h.owner_user_id = :user
                                """, params)),
                new Part("hosting", "history", rows.list(jdbc, """
                                select c.host_id, c.from_status, c.to_status, c.reason, c.created_at
                                from hosts.status_changes c join hosts.hosts h on h.id = c.host_id
                                where h.owner_user_id = :user order by c.created_at
                                """, params)),
                new Part("hosting", "links", rows.list(jdbc, """
                                select machine_name, os, agent_version, gpus, created_at, approved_at, denied_at,
                                       host_id
                                from hosts.host_links where approved_by = :user or denied_by = :user
                                order by created_at
                                """, params)));
    }

    @Override
    public List<Blocker> blockers(UUID userId) {
        var blockers = new ArrayList<Blocker>();
        for (var host : liveHosts(userId)) {
            if (work.isBusy(host.getKey())) {
                blockers.add(new Blocker(
                        "host-busy",
                        "Your machine " + host.getValue() + " is running someone's session. Pause it so it takes no "
                                + "new ones, and wait for this one to end.",
                        null));
            }
        }
        return blockers;
    }

    private List<Map.Entry<UUID, String>> liveHosts(UUID userId) {
        return jdbc.sql("select id, name from hosts.hosts where owner_user_id = :user and status <> 'removed'")
                .param("user", userId)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)))
                .list();
    }

    @Override
    @Transactional
    public void erase(UUID userId, ErasureStep step) {
        var params = Map.of("user", userId);
        var ofTheirHosts = "host_id in (select id from hosts.hosts where owner_user_id = :user)";
        switch (step) {
            case REMOVE_HOSTS -> {
                for (var host : liveHosts(userId)) {
                    lifecycle.remove(userId, host.getKey(), ERASED);
                }
            }
            case DELETE_RECORDS -> {
                jdbc.sql("""
                                delete from hosts.install_events where install_id in (
                                    select install_id from hosts.host_links
                                    where install_id is not null
                                      and (approved_by = :user or denied_by = :user or %s))
                                """.formatted(ofTheirHosts)).params(params).update();
                jdbc.sql("delete from hosts.host_links where approved_by = :user or denied_by = :user or "
                                + ofTheirHosts)
                        .params(params)
                        .update();
                for (var table : List.of(
                        "hosts.host_gpus",
                        "hosts.self_tests",
                        "hosts.self_test_results",
                        "hosts.self_test_runs",
                        "hosts.host_scores",
                        "hosts.update_results",
                        "hosts.enrolment_codes")) {
                    jdbc.sql("delete from " + table + " where " + ofTheirHosts)
                            .params(params)
                            .update();
                }
                jdbc.sql("delete from hosts.idempotency_keys where user_id = :user")
                        .params(params)
                        .update();
                jdbc.sql("update hosts.hosts set name = '' where owner_user_id = :user and name <> ''")
                        .params(params)
                        .update();
            }
            case LOCK, DELETE_FILES, DE_IDENTIFY -> {}
        }
    }
}
