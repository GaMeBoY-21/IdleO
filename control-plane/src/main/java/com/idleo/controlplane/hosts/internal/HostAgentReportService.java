package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.HostAgentReports;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records what agents report about their machines. See {@link HostAgentReports}. */
@Service
class HostAgentReportService implements HostAgentReports {

    static final Duration SEEN_WRITE_INTERVAL = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final Clock clock;

    HostAgentReportService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void recordHello(UUID hostId, AgentHello hello) {
        var now = Timestamp.from(Instant.now(clock));
        jdbc.sql("""
                        update hosts.hosts set os = :os, agent_version = :agentVersion, last_seen_at = :now
                        where id = :hostId
                        """)
                .param("os", hello.os())
                .param("agentVersion", hello.agentVersion())
                .param("now", now)
                .param("hostId", hostId)
                .update();
        for (var gpu : hello.gpus()) {
            jdbc.sql("""
                            insert into hosts.host_gpus
                                (host_id, gpu_uuid, model, vram_mb, driver_version, cuda_driver_version,
                                 compute_capability, first_seen_at, last_reported_at, gone_at)
                            values (:hostId, :uuid, :model, :vramMb, :driver, :cuda, :capability, :now, :now, null)
                            on conflict (host_id, gpu_uuid) do update
                            set model = excluded.model, vram_mb = excluded.vram_mb,
                                driver_version = excluded.driver_version,
                                cuda_driver_version = excluded.cuda_driver_version,
                                compute_capability = excluded.compute_capability,
                                last_reported_at = excluded.last_reported_at, gone_at = null
                            """)
                    .param("hostId", hostId)
                    .param("uuid", gpu.uuid())
                    .param("model", gpu.model())
                    .param("vramMb", gpu.vramMb())
                    .param("driver", gpu.driverVersion())
                    .param("cuda", gpu.cudaDriverVersion())
                    .param("capability", gpu.computeCapability())
                    .param("now", now)
                    .update();
        }
        jdbc.sql("""
                        update hosts.host_gpus set gone_at = :now
                        where host_id = :hostId and gone_at is null and gpu_uuid <> all (:reported)
                        """)
                .param("now", now)
                .param("hostId", hostId)
                .param("reported", hello.gpus().stream().map(ReportedGpu::uuid).toArray(String[]::new))
                .update();
    }

    @Override
    public boolean recordSeen(UUID hostId) {
        var now = Instant.now(clock);
        jdbc.sql("""
                        update hosts.hosts set last_seen_at = :now
                        where id = :hostId and (last_seen_at is null or last_seen_at <= :cutoff)
                        """)
                .param("now", Timestamp.from(now))
                .param("cutoff", Timestamp.from(now.minus(SEEN_WRITE_INTERVAL)))
                .param("hostId", hostId)
                .update();
        return !jdbc.sql("""
                        select exists (select 1 from hosts.hosts where id = :hostId and status = 'removed')
                            or not exists (select 1 from hosts.host_certificates
                                           where host_id = :hostId and revoked_at is null)
                        """).param("hostId", hostId).query(Boolean.class).single();
    }
}
