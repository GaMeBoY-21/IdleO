package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import gpu.agent.v1.Host.SelfTestCheck;
import gpu.agent.v1.Host.SelfTestProgress;
import gpu.agent.v1.Host.SelfTestReport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * "Run self-test" on a machine's page (ADR 0034), against a real agent stream: the owner and an admin can ask, each
 * check shows as it arrives, and the page says why when it can't: offline, busy, one already running or the day's six
 * used up.
 */
@ControlPlaneTest
class SelfTestRunTests {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @LocalGrpcServerPort
    int grpcPort;

    private ConnectedHost host;

    @AfterEach
    void disconnect() {
        if (host != null) {
            host.disconnect();
        }
    }

    private ConnectedHost online() throws Exception {
        host = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        return host;
    }

    private ResultActions press(ConnectedHost h) throws Exception {
        return mvc.perform(post("/v1/hosts/" + h.id() + "/self-tests")
                .header("Authorization", h.agent().bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private ResultActions selfTest(ConnectedHost h) throws Exception {
        return mvc.perform(get("/v1/hosts/" + h.id() + "/self-test").header("Authorization", h.agent().bearer));
    }

    private String awaitRunId(ConnectedHost h) throws Exception {
        var command = h.stream().await(message -> message.hasRunSelfTest(), WAIT);
        h.ack(command);
        return command.getRunSelfTest().getRunId();
    }

    private void progress(ConnectedHost h, String runId, String name, boolean passed) throws Exception {
        var message = TestAgent.request()
                .setSelfTestProgress(SelfTestProgress.newBuilder()
                        .setRunId(runId)
                        .setCheck(SelfTestCheck.newBuilder()
                                .setName(name)
                                .setPassed(passed)
                                .setDetail(name + " detail")
                                .setTook(com.google.protobuf.Duration.newBuilder()
                                        .setNanos(250_000_000))))
                .build();
        h.stream().send(message);
        h.stream()
                .await(
                        reply -> reply.hasAck()
                                && reply.getAck().getAckedMessageId().equals(message.getMessageId()),
                        WAIT);
    }

    private void report(ConnectedHost h, String runId, boolean passed) throws Exception {
        var message = TestAgent.request()
                .setSelfTestReport(SelfTestReport.newBuilder()
                        .setPassed(passed)
                        .setRunId(runId)
                        .setUploadKbps(40_000)
                        .setDownloadKbps(90_000)
                        .addChecks(SelfTestCheck.newBuilder()
                                .setName("gpu_visible")
                                .setPassed(true))
                        .addChecks(
                                SelfTestCheck.newBuilder().setName("lockdown").setPassed(passed)))
                .build();
        h.stream().send(message);
        h.stream()
                .await(
                        reply -> reply.hasAck()
                                && reply.getAck().getAckedMessageId().equals(message.getMessageId()),
                        WAIT);
    }

    @Test
    void anOnlineMachineRunsItAndEachCheckShowsAsItArrives() throws Exception {
        var h = online();
        selfTest(h)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canRun").value(true))
                .andExpect(jsonPath("$.run").doesNotExist())
                .andExpect(jsonPath("$.perDay").value(6));

        press(h).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.run.state").value("running"))
                .andExpect(jsonPath("$.run.byAdmin").value(false))
                .andExpect(jsonPath("$.canRun").value(false))
                .andExpect(jsonPath("$.whyNot.code").value("self-test-running"));
        var runId = awaitRunId(h);

        progress(h, runId, "distro_registered", true);
        selfTest(h)
                .andExpect(jsonPath("$.run.id").value(runId))
                .andExpect(jsonPath("$.run.checks.length()").value(1))
                .andExpect(jsonPath("$.run.checks[0].name").value("distro_registered"))
                .andExpect(jsonPath("$.run.checks[0].tookMs").value(250));
        progress(h, runId, "gpu_visible", true);
        // A resent check isn't shown twice.
        progress(h, runId, "gpu_visible", true);
        selfTest(h)
                .andExpect(jsonPath("$.run.checks.length()").value(2))
                .andExpect(jsonPath("$.run.state").value("running"));

        report(h, runId, true);
        selfTest(h)
                .andExpect(jsonPath("$.run.state").value("passed"))
                .andExpect(jsonPath("$.run.checks.length()").value(2))
                .andExpect(jsonPath("$.run.finishedAt").exists())
                .andExpect(jsonPath("$.canRun").value(true))
                .andExpect(jsonPath("$.usedToday").value(1));
        // The report is also the host's latest self-test and one more in its record for the score.
        assertThat(jdbc.queryForObject(
                        "select download_kbps from hosts.self_tests where host_id = ?", Integer.class, h.id()))
                .isEqualTo(90_000);
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.self_test_results where host_id = ? and passed",
                        Integer.class,
                        h.id()))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "select self_tests_passed from hosts.host_scores where host_id = ?", Integer.class, h.id()))
                .isOne();
    }

    @Test
    void aFailedRunIsShownAsFailedWithItsChecks() throws Exception {
        var h = online();
        press(h).andExpect(status().isAccepted());
        var runId = awaitRunId(h);

        report(h, runId, false);

        selfTest(h)
                .andExpect(jsonPath("$.run.state").value("failed"))
                .andExpect(jsonPath("$.run.checks[1].passed").value(false));
        assertThat(jdbc.queryForObject(
                        "select self_tests_failed from hosts.host_scores where host_id = ?", Integer.class, h.id()))
                .isOne();
    }

    @Test
    void anOfflineMachineIsToldInPlainWordsAndNothingIsStarted() throws Exception {
        var agent = TestAgent.enrol(mvc, grpcPort);
        var offline = new ConnectedHost(agent, null, null, null, null, null);

        press(offline)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/host-offline"))
                .andExpect(jsonPath("$.detail")
                        .value("This machine isn't connected right now. Open IdleO on it, then try again."));
        selfTest(offline)
                .andExpect(jsonPath("$.canRun").value(false))
                .andExpect(jsonPath("$.whyNot.code").value("host-offline"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.self_test_runs where host_id = ?", Integer.class, agent.hostId))
                .isZero();
    }

    @Test
    void aMachineRunningASessionCannotRunOneAndSaysWhy() throws Exception {
        var h = online();
        var at = Timestamp.from(Instant.now());
        var sessionId = UUID.randomUUID();
        jdbc.update("""
                insert into sessions.sessions (id, renter_id, host_id, type, state, attempt, template_id, image_digest,
                    vram_needed_mb, max_price_paise_per_min, price_paise_per_min, max_spend_paise, max_runtime_seconds,
                    idempotency_key, request_hash, state_changed_at, version, created_at)
                values (?, ?, ?, 'batch', 'RUNNING', 1, 'cuda-smoke-test', 'img@sha256:abc', 1000, 5, 2, 1000, 3600,
                    ?, 'hash', ?, 1, ?)
                """, sessionId, UUID.randomUUID(), h.id(), sessionId.toString(), at, at);

        press(h).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/host-busy"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("session is running")));
        selfTest(h)
                .andExpect(jsonPath("$.canRun").value(false))
                .andExpect(jsonPath("$.whyNot.code").value("host-busy"));

        jdbc.update("update sessions.sessions set state = 'COMPLETED' where id = ?", sessionId);
        press(h).andExpect(status().isAccepted());
    }

    @Test
    void onlyOneRunsAtATimeAndTwoPressesAtOnceStartOne() throws Exception {
        var h = online();
        press(h).andExpect(status().isAccepted());
        press(h).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("/problems/self-test-running"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from hosts.self_test_runs where host_id = ?", Integer.class, h.id()))
                .isOne();
        // The agent gets one command.
        awaitRunId(h);
        assertThat(h.stream().receives(message -> message.hasRunSelfTest(), Duration.ofSeconds(1)))
                .isFalse();
    }

    @Test
    void aRunTheAgentNeverAnsweredStopsCountingAfterTenMinutes() throws Exception {
        var h = online();
        press(h).andExpect(status().isAccepted());
        awaitRunId(h);
        jdbc.update(
                "update hosts.self_test_runs set requested_at = ? where host_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(11))),
                h.id());

        selfTest(h)
                .andExpect(jsonPath("$.run.state").value("timed_out"))
                .andExpect(jsonPath("$.canRun").value(true));
        press(h).andExpect(status().isAccepted());
    }

    @Test
    void anAgentThatWontRunItIsShownAsRefused() throws Exception {
        var h = online();
        press(h).andExpect(status().isAccepted());
        var runId = awaitRunId(h);

        var refusal = TestAgent.request()
                .setSelfTestProgress(
                        SelfTestProgress.newBuilder().setRunId(runId).setRefused("session_running"))
                .build();
        h.stream().send(refusal);
        h.stream()
                .await(
                        reply -> reply.hasAck()
                                && reply.getAck().getAckedMessageId().equals(refusal.getMessageId()),
                        WAIT);

        selfTest(h)
                .andExpect(jsonPath("$.run.state").value("refused"))
                .andExpect(jsonPath("$.run.refused").value("session_running"))
                .andExpect(jsonPath("$.canRun").value(true));
    }

    @Test
    void sixRunsADayAndTheSeventhWaits() throws Exception {
        var h = online();
        for (int i = 0; i < 6; i++) {
            press(h).andExpect(status().isAccepted());
            report(h, awaitRunId(h), true);
        }

        press(h).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/self-test-limit"));
        selfTest(h)
                .andExpect(jsonPath("$.canRun").value(false))
                .andExpect(jsonPath("$.whyNot.code").value("self-test-limit"))
                .andExpect(jsonPath("$.usedToday").value(6));

        // A day after the oldest, one is free again.
        jdbc.update("""
                update hosts.self_test_runs set requested_at = ?
                where id = (select id from hosts.self_test_runs where host_id = ? order by requested_at limit 1)
                """, Timestamp.from(Instant.now().minus(Duration.ofHours(25))), h.id());
        press(h).andExpect(status().isAccepted());
    }

    @Test
    void anAdminCanRunItAndItIsAuditedWithTheirReasonWhileTheOwnersOwnPressIsNot() throws Exception {
        var h = online();
        var admin = TestAdmins.signIn(mvc, jdbc, clock);

        mvc.perform(admin.as(post("/admin/v1/hosts/" + h.id() + "/self-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(admin.as(post("/admin/v1/hosts/" + h.id() + "/self-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"checking the GPU after a driver update\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.state").value("running"))
                .andExpect(jsonPath("$.run.byAdmin").value(true));
        var runId = awaitRunId(h);

        assertThat(jdbc.queryForObject(
                        "select reason from identity.audit_log where action = 'host.run_self_test' and target_id = ?",
                        String.class,
                        h.id().toString()))
                .isEqualTo("checking the GPU after a driver update");
        progress(h, runId, "gpu_visible", true);
        mvc.perform(admin.as(get("/admin/v1/hosts/" + h.id() + "/self-test")))
                .andExpect(jsonPath("$.run.checks[0].name").value("gpu_visible"));
        report(h, runId, true);

        press(h).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'host.run_self_test' and target_id = ?",
                        Integer.class,
                        h.id().toString()))
                .isOne();
    }

    @Test
    void anotherPersonsMachineIsNotFound() throws Exception {
        var h = online();
        var stranger = TestUsers.signUp(mvc);

        mvc.perform(post("/v1/hosts/" + h.id() + "/self-tests")
                        .header("Authorization", stranger.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/hosts/" + h.id() + "/self-test").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
    }
}
