package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.hosts.HostGpus;
import com.idleo.controlplane.hosts.HostOwners;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Hosts and their enrolment codes, always scoped to the owner. */
@Service
class HostService implements HostOwners, HostGpus {

    static final Duration CODE_LIFETIME = Duration.ofHours(1);

    private final HostRepository hosts;
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    HostService(HostRepository hosts, JdbcClient jdbc, IdGenerator ids, Clock clock) {
        this.hosts = hosts;
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    record IssuedCode(String code, Instant expiresAt) {}

    /** A GPU the host's agent reported; gone once a later Hello left it out. */
    record ReportedGpu(String model, int vramMb, boolean gone) {}

    Host create(UUID ownerUserId, String name) {
        return hosts.save(Host.newHost(ids.newId(), ownerUserId, name, Instant.now(clock)));
    }

    @Override
    public boolean owns(UUID userId, UUID hostId) {
        return find(userId, hostId).isPresent();
    }

    @Override
    public Optional<Gpu> gpu(UUID hostId, String gpuUuid) {
        return jdbc.sql("select model, vram_mb from hosts.host_gpus where host_id = :hostId and gpu_uuid = :gpuUuid")
                .param("hostId", hostId)
                .param("gpuUuid", gpuUuid)
                .query((rs, n) -> new Gpu(rs.getString("model"), rs.getInt("vram_mb")))
                .optional();
    }

    /** The owner's host, removed or not: the ledger still points at a removed one. */
    @Override
    public Optional<Gpu> largest(UUID hostId) {
        return jdbc.sql("""
                        select model, vram_mb from hosts.host_gpus where host_id = :hostId and gone_at is null
                        order by vram_mb desc, gpu_uuid limit 1
                        """)
                .param("hostId", hostId)
                .query((rs, n) -> new Gpu(rs.getString("model"), rs.getInt("vram_mb")))
                .optional();
    }

    Optional<Host> find(UUID ownerUserId, UUID hostId) {
        return hosts.findByIdAndOwnerUserId(hostId, ownerUserId);
    }

    @Override
    public List<OwnedHost> ownedBy(UUID userId) {
        return hosts.findAllByOwnerUserIdOrderByCreatedAt(userId).stream()
                .map(host -> new OwnedHost(host.id(), host.name(), host.removed()))
                .toList();
    }

    /** The owner's hosts, not the ones they removed. */
    List<Host> list(UUID ownerUserId) {
        return hosts.findAllByOwnerUserIdAndRemovedAtIsNullOrderByCreatedAt(ownerUserId);
    }

    /** The GPUs each of these hosts reported, in the order they were first seen. */
    Map<UUID, List<ReportedGpu>> gpus(Collection<UUID> hostIds) {
        if (hostIds.isEmpty()) {
            return Map.of();
        }
        var gpus = new HashMap<UUID, List<ReportedGpu>>();
        jdbc.sql("""
                        select host_id, model, vram_mb, gone_at from hosts.host_gpus
                        where host_id in (:hostIds)
                        order by first_seen_at, gpu_uuid
                        """).param("hostIds", hostIds).query(rs -> {
            gpus.computeIfAbsent(rs.getObject("host_id", UUID.class), id -> new ArrayList<>())
                    .add(new ReportedGpu(
                            rs.getString("model"), rs.getInt("vram_mb"), rs.getTimestamp("gone_at") != null));
        });
        return gpus;
    }

    /** A new one-time code for the host, replacing any earlier one; empty if it isn't the owner's. */
    @Transactional
    Optional<IssuedCode> issueCode(UUID ownerUserId, UUID hostId) {
        if (find(ownerUserId, hostId).isEmpty()) {
            return Optional.empty();
        }
        var now = Instant.now(clock);
        var code = EnrolmentCodes.generate(random);
        var expiresAt = now.plus(CODE_LIFETIME);
        jdbc.sql("""
                        insert into hosts.enrolment_codes (host_id, code_hash, expires_at, created_at, used_at)
                        values (:hostId, :hash, :expiresAt, :now, null)
                        on conflict (host_id) do update
                        set code_hash = excluded.code_hash, expires_at = excluded.expires_at,
                            created_at = excluded.created_at, used_at = null
                        """)
                .param("hostId", hostId)
                .param(
                        "hash",
                        EnrolmentCodes.hash(EnrolmentCodes.normalise(code).orElseThrow()))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now))
                .update();
        return Optional.of(new IssuedCode(code, expiresAt));
    }
}
