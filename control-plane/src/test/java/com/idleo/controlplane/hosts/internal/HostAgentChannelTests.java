package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.protobuf.Timestamp;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.HostEarningsChanged;
import com.idleo.controlplane.billing.internal.TestCredit;
import com.idleo.controlplane.hosts.HostAgentReports;
import com.idleo.controlplane.identity.AuditTrail;
import gpu.agent.v1.Host.HostState;
import gpu.agent.v1.Host.HostStatus;
import gpu.agent.v1.Host.Leave;
import gpu.agent.v1.Host.SelfTestCheck;
import gpu.agent.v1.Host.SelfTestReport;
import gpu.agent.v1.Host.SetPause;
import io.grpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the agent asks of the control plane for its owner, and what it's told back (ADR 0034): a tray pause is the web
 * app's pause, leaving removes the host as Remove does, a self-test is stored (and activates a pending host only with
 * {@code hosts.auto-activate} on), and {@code HostStatus} follows every change.
 */
@ControlPlaneTest
class HostAgentChannelTests {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    MutableClock clock;

    @Autowired
    HostActivation activation;

    @Autowired
    HostLinkProperties linkProperties;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ApplicationContext context;

    @LocalGrpcServerPort
    int grpcPort;

    private static HostStatus awaitStatus(TestHosts.ConnectedHost host, HostState state) throws InterruptedException {
        return host.stream()
                .await(
                        message -> message.hasHostStatus()
                                && message.getHostStatus().getState() == state,
                        WAIT)
                .getHostStatus();
    }

    @Test
    void theAgentIsToldWhereItsHostStandsAfterWelcomeAndAfterEachChange() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            awaitStatus(host, HostState.HOST_STATE_PENDING_APPROVAL);
            TestHosts.list(mvc, host.agent(), 10, 85);

            activation.activate(host.id(), "tested", AuditTrail.Actor.operator("test"));
            var active = awaitStatus(host, HostState.HOST_STATE_ACTIVE);
            assertThat(active.getEarnedTodayPaise()).isZero();
            assertThat(active.hasPausedUntil()).isFalse();

            // A pause from the web app reaches the tray, with its end.
            var until = Instant.now(clock).plus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            mvc.perform(post("/v1/hosts/" + host.id() + "/pause")
                            .header("Authorization", host.agent().bearer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"until\":\"" + until + "\"}"))
                    .andExpect(status().isOk());
            var paused = awaitStatus(host, HostState.HOST_STATE_PAUSED);
            assertThat(paused.getPausedUntil().getSeconds()).isEqualTo(until.getEpochSecond());

            mvc.perform(post("/v1/hosts/" + host.id() + "/resume").header("Authorization", host.agent().bearer))
                    .andExpect(status().isOk());
            awaitStatus(host, HostState.HOST_STATE_ACTIVE);

            // Earnings changing sends today's figure again.
            host.stream().clear();
            transactions.executeWithoutResult(
                    status -> events.publishEvent(new HostEarningsChanged(List.of(host.id()))));
            awaitStatus(host, HostState.HOST_STATE_ACTIVE);
        } finally {
            host.disconnect();
        }
    }

    @Test
    void aPauseFromTheTrayIsTheSamePauseTheWebAppShows() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 10);
        try {
            var until = Instant.now(clock).plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            var pause = TestAgent.request()
                    .setSetPause(SetPause.newBuilder()
                            .setUntil(Timestamp.newBuilder().setSeconds(until.getEpochSecond())))
                    .build();
            host.stream().send(pause);
            host.stream()
                    .await(
                            message -> message.hasAck()
                                    && message.getAck().getAckedMessageId().equals(pause.getMessageId()),
                            WAIT);
            mvc.perform(get("/v1/hosts/" + host.id()).header("Authorization", host.agent().bearer))
                    .andExpect(jsonPath("$.paused").value(true))
                    .andExpect(jsonPath("$.pausedUntil").value(until.toString()));
            awaitStatus(host, HostState.HOST_STATE_PAUSED);

            // Hosting off: a pause with no end, then on again.
            host.stream()
                    .send(TestAgent.request()
                            .setSetPause(SetPause.getDefaultInstance())
                            .build());
            awaitStatus(host, HostState.HOST_STATE_PAUSED);
            mvc.perform(get("/v1/hosts/" + host.id()).header("Authorization", host.agent().bearer))
                    .andExpect(jsonPath("$.paused").value(true))
                    .andExpect(jsonPath("$.pausedUntil").doesNotExist());
            host.stream()
                    .send(TestAgent.request()
                            .setSetPause(SetPause.newBuilder().setResume(true))
                            .build());
            awaitStatus(host, HostState.HOST_STATE_ACTIVE);
            mvc.perform(get("/v1/hosts/" + host.id()).header("Authorization", host.agent().bearer))
                    .andExpect(jsonPath("$.paused").value(false));

            // A pause past 7 days is refused, acked (sending it again can't help), and the tray hears it's still
            // active.
            host.stream().clear();
            var tooLong = TestAgent.request()
                    .setSetPause(SetPause.newBuilder()
                            .setUntil(Timestamp.newBuilder()
                                    .setSeconds(Instant.now(clock)
                                            .plus(Duration.ofDays(8))
                                            .getEpochSecond())))
                    .build();
            host.stream().send(tooLong);
            host.stream()
                    .await(
                            message -> message.hasAck()
                                    && message.getAck().getAckedMessageId().equals(tooLong.getMessageId()),
                            WAIT);
            awaitStatus(host, HostState.HOST_STATE_ACTIVE);
        } finally {
            host.disconnect();
        }
    }

    @Test
    void leavingRemovesTheHostAndRevokesItsCertificate() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 10);
        host.stream()
                .send(TestAgent.request().setLeave(Leave.getDefaultInstance()).build());
        var ended = host.stream().endStatus(WAIT);
        assertThat(ended).isNotNull();
        assertThat(ended.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(jdbc.queryForObject("select status from hosts.hosts where id = ?", String.class, host.id()))
                .isEqualTo("removed");
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.host_certificates where host_id = ? and revoked_at is null",
                        Integer.class,
                        host.id()))
                .isZero();
        host.disconnect();
    }

    @Test
    void aHostRunningASessionLeavesOnlyOnceItHasStopped() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 5);
        try {
            var renter = TestUsers.signUp(mvc);
            TestCredit.grant(context, renter.id(), 50_000);
            mvc.perform(post("/v1/sessions")
                            .header("Authorization", renter.bearer())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 5, "maxSpendPaise": 10000}
                                    """))
                    .andExpect(status().isCreated());
            host.awaitOffer(WAIT);
            var leave = TestAgent.request().setLeave(Leave.getDefaultInstance()).build();
            host.stream().send(leave);
            // No ack while it's busy, so the agent sends it again later; the host stays.
            assertThat(host.stream()
                            .receives(
                                    message -> message.hasAck()
                                            && message.getAck()
                                                    .getAckedMessageId()
                                                    .equals(leave.getMessageId()),
                                    Duration.ofSeconds(1)))
                    .isFalse();
            assertThat(jdbc.queryForObject("select status from hosts.hosts where id = ?", String.class, host.id()))
                    .isEqualTo("active");
        } finally {
            host.disconnect();
        }
    }

    @Test
    void theSpeedCheckGetsAGrantForTheHostsOneObjectOnly() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            host.stream()
                    .send(TestAgent.request()
                            .setSpeedTestRequest(gpu.agent.v1.Host.SpeedTestRequest.getDefaultInstance())
                            .build());
            var answer = host.stream()
                    .await(gpu.agent.v1.AgentGateway.OpenStreamResponse::hasSpeedTestGrant, WAIT)
                    .getSpeedTestGrant();
            assertThat(answer.getKey()).isEqualTo("speedtest/" + host.id() + "/probe");
            assertThat(answer.getGrant().getPrefixesList()).containsExactly("speedtest/" + host.id() + "/");
            assertThat(answer.getGrant().getPermission())
                    .isEqualTo(gpu.agent.v1.Session.StoragePermission.STORAGE_PERMISSION_READ_WRITE);
            assertThat(answer.getGrant().getSessionToken()).isNotEmpty();
        } finally {
            host.disconnect();
        }
    }

    @Test
    void aSelfTestIsStoredAndShownToAdmins() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            var report = TestAgent.request()
                    .setSelfTestReport(SelfTestReport.newBuilder()
                            .setPassed(false)
                            .setDistroVersion("0.1.0")
                            .addChecks(SelfTestCheck.newBuilder()
                                    .setName("distro_registered")
                                    .setPassed(true)
                                    .setDetail("IdleO-0.1.0, WSL 2")
                                    .setTook(com.google.protobuf.Duration.newBuilder()
                                            .setNanos(120_000_000)))
                            .addChecks(SelfTestCheck.newBuilder()
                                    .setName("gpu_visible")
                                    .setPassed(false)
                                    .setDetail("nvidia-smi found no GPU")))
                    .build();
            host.stream().send(report);
            host.stream()
                    .await(
                            message -> message.hasAck()
                                    && message.getAck().getAckedMessageId().equals(report.getMessageId()),
                            WAIT);
            var admin = TestAdmins.signIn(mvc, jdbc, clock);
            mvc.perform(admin.as(get("/admin/v1/hosts/" + host.id())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.selfTest.passed").value(false))
                    .andExpect(jsonPath("$.selfTest.distroVersion").value("0.1.0"))
                    .andExpect(jsonPath("$.selfTest.agentVersion").value("0.1.0"))
                    .andExpect(jsonPath("$.selfTest.checks[0].name").value("distro_registered"))
                    .andExpect(jsonPath("$.selfTest.checks[0].tookMs").value(120))
                    .andExpect(jsonPath("$.selfTest.checks[1].passed").value(false))
                    .andExpect(jsonPath("$.lastUpdate").doesNotExist());

            // Claiming to pass while a check failed breaks the protocol: dropped, never acked.
            var lie = TestAgent.request()
                    .setSelfTestReport(report.getSelfTestReport().toBuilder().setPassed(true))
                    .build();
            host.stream().send(lie);
            assertThat(host.stream()
                            .receives(
                                    message -> message.hasAck()
                                            && message.getAck()
                                                    .getAckedMessageId()
                                                    .equals(lie.getMessageId()),
                                    Duration.ofSeconds(1)))
                    .isFalse();
        } finally {
            host.disconnect();
        }
    }

    @Test
    void aPassedSelfTestActivatesAPendingHostOnlyWithAutoActivationOn() throws Exception {
        var host = TestHosts.connect(mvc, grpcPort);
        try {
            TestHosts.list(mvc, host.agent(), 10, 85);
            var passed = new HostAgentReports.SelfTest(
                    true, "0.1.0", List.of(new HostAgentReports.SelfTestCheck("disk", true, "120 GB free", 5)));

            // The pilot's setting (the application's own bean): stored, and the host stays pending.
            assertThat(linkProperties.autoActivate()).isFalse();
            context.getBean(HostAgentReports.class).recordSelfTest(host.id(), passed);
            assertThat(hostStatus(host.id())).isEqualTo("pending");

            // After the pilot: the same report activates it, through the admin console's Approve, audited.
            var after = new HostAgentReportService(
                    jdbcClient, clock, activation, new HostLinkProperties(linkProperties.link(), true));
            after.recordSelfTest(host.id(), new HostAgentReports.SelfTest(false, "0.1.0", List.of()));
            assertThat(hostStatus(host.id())).isEqualTo("pending");
            after.recordSelfTest(host.id(), passed);
            assertThat(hostStatus(host.id())).isEqualTo("active");
            assertThat(jdbc.queryForObject(
                            "select reason from hosts.status_changes where host_id = ? and to_status = 'active'",
                            String.class,
                            host.id()))
                    .isEqualTo(HostAgentReportService.SELF_TEST_PASSED);
            awaitStatus(host, HostState.HOST_STATE_ACTIVE);
        } finally {
            host.disconnect();
        }
    }

    private String hostStatus(UUID hostId) {
        return jdbc.queryForObject("select status from hosts.hosts where id = ?", String.class, hostId);
    }
}
