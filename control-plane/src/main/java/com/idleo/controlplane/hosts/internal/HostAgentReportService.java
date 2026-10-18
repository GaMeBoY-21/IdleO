package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.HostAgentReports;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records what agents report about their machines. See {@link HostAgentReports}. A passed self-test activates a
 * pending host only with {@code hosts.auto-activate} on, through {@link HostActivation}, the admin console's Approve;
 * during the invite-only pilot it is off, and the admin console shows the self-test beside Approve.
 */
@Service
class HostAgentReportService implements HostAgentReports {

    private static final Logger log = LoggerFactory.getLogger(HostAgentReportService.class);
    static final Duration SEEN_WRITE_INTERVAL = Duration.ofMinutes(1);
    static final String SELF_TEST_PASSED = "its self-test passed";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final HostActivation activation;
    private final HostLinkProperties settings;
    private final JsonMapper json = JsonMapper.builder().build();

    HostAgentReportService(JdbcClient jdbc, Clock clock, HostActivation activation, HostLinkProperties settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.activation = activation;
        this.settings = settings;
    }

    @Override
    public void recordSelfTest(UUID hostId, SelfTest selfTest) {
        var now = Timestamp.from(Instant.now(clock));
        jdbc.sql("""
                        insert into hosts.self_tests (host_id, passed, distro_version, agent_version, checks, reported_at)
                        values (:hostId, :passed, :distro, (select agent_version from hosts.hosts where id = :hostId),
                                cast(:checks as jsonb), :now)
                        on conflict (host_id) do update
                        set passed = excluded.passed, distro_version = excluded.distro_version,
                            agent_version = excluded.agent_version, checks = excluded.checks,
                            reported_at = excluded.reported_at
                        """)
                .param("hostId", hostId)
                .param("passed", selfTest.passed())
                .param("distro", selfTest.distroVersion())
                .param("checks", json.writeValueAsString(selfTest.checks()))
                .param("now", now)
                .update();
        if (!selfTest.passed() || !settings.autoActivate()) {
            return;
        }
        var status = jdbc.sql("select status from hosts.hosts where id = :hostId")
                .param("hostId", hostId)
                .query(String.class)
                .optional();
        if (status.isPresent() && status.get().equals(HostStatus.PENDING.dbValue())) {
            try {
                activation.activate(hostId, SELF_TEST_PASSED, AuditTrail.Actor.system("self-test"));
                log.info("Host {} activated: {}", hostId, SELF_TEST_PASSED);
            } catch (HostActivation.Refused refused) {
                log.warn("Host {} passed its self-test but can't be activated: {}", hostId, refused.getMessage());
            }
        }
    }

    @Override
    public void recordUpdateResult(UUID hostId, String version, UpdateOutcome outcome, String detail) {
        jdbc.sql("""
                        insert into hosts.update_results (host_id, version, outcome, detail, reported_at)
                        values (:hostId, :version, :outcome, :detail, :now)
                        on conflict (host_id, version) do update
                        set outcome = excluded.outcome, detail = excluded.detail, reported_at = excluded.reported_at
                        """)
                .param("hostId", hostId)
                .param("version", version)
                .param("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .param("detail", detail)
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
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
