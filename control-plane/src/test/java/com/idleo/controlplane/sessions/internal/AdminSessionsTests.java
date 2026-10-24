package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.identity.AuditTrail;
import gpu.agent.v1.AgentGateway.OpenStreamRequest;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Session.StopReason;
import gpu.agent.v1.Session.UsageReport;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The sessions module's admin actions (ADR 0027): sessions found and read, and a running one force-stopped. */
@ControlPlaneTest
class AdminSessionsTests {

    static final Duration WAIT = TestBookings.WAIT;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    SessionForceStops forceStops;

    @Autowired
    TransactionTemplate transactions;

    @LocalGrpcServerPort
    int grpcPort;

    private final JsonMapper json = JsonMapper.builder().build();
    private TestBookings bookings;

    @BeforeEach
    void bookings() {
        bookings = new TestBookings(mvc, context, jdbc, grpcPort);
    }

    @AfterEach
    void disconnect() {
        bookings.close();
        clock.reset();
    }

    private static MockHttpServletRequestBuilder reason(MockHttpServletRequestBuilder request, String reason) {
        return request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}");
    }

    private JsonNode read(MockHttpServletRequestBuilder request, TestAdmins.TestAdmin admin) throws Exception {
        return json.readTree(mvc.perform(admin.as(request))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private void send(TestBookings.Booked booked, OpenStreamRequest request) throws InterruptedException {
        booked.host().stream().send(request);
        booked.host().stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        WAIT);
    }

    private void report(TestBookings.Booked booked, SessionState state) throws InterruptedException {
        send(
                booked,
                TestAgent.request()
                        .setSessionStatus(SessionStatus.newBuilder()
                                .setSessionId(booked.sessionId().toString())
                                .setAttempt(1)
                                .setState(state))
                        .build());
    }

    private TestBookings.Booked running() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(WAIT));
        report(booked, SessionState.SESSION_STATE_RUNNING);
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (jdbc.queryForObject(
                                "select count(*) from metering.metered_sessions where session_id = ?",
                                Integer.class,
                                booked.sessionId())
                        == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return booked;
    }

    private int audited(TestBookings.Booked booked) {
        return jdbc.queryForObject(
                "select count(*) from identity.audit_log where action = 'session.force_stop' and target_id = ?",
                Integer.class,
                booked.sessionId().toString());
    }

    @Test
    void aForceStopEndsTheJobAtOnceAndSettlesTheMinutesItRanOnce() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var booked = running();
        // One minute both witnesses saw.
        clock.advance(Duration.ofSeconds(61));
        send(
                booked,
                TestAgent.request()
                        .setUsageReport(UsageReport.newBuilder()
                                .setSessionId(booked.sessionId().toString())
                                .setAttempt(1)
                                .setMinuteIndex(0)
                                .setGpuSeconds(60)
                                .setAvgUtilPct(90))
                        .build());

        var stopping = read(
                reason(post("/admin/v1/sessions/" + booked.sessionId() + "/stop"), "mining crypto on a pilot host"),
                admin);
        assertThat(stopping.get("session").get("state").asString()).isEqualTo("FINISHING");
        read(
                reason(post("/admin/v1/sessions/" + booked.sessionId() + "/stop"), "mining crypto on a pilot host"),
                admin);
        assertThat(audited(booked)).isOne();

        var command = booked.host().stream().await(OpenStreamResponse::hasStopSession, WAIT);
        assertThat(command.getStopSession().getReason()).isEqualTo(StopReason.STOP_REASON_ABUSE);
        assertThat(command.getStopSession().getCheckpointFirst()).isFalse();
        booked.host().ack(command);
        report(booked, SessionState.SESSION_STATE_STOPPED);

        var completed = bookings.awaitState(booked.renter(), booked.sessionId(), "COMPLETED");
        assertThat(completed.get("events").findValuesAsString("reason"))
                .containsSubsequence("running", "operator_stopped", "stopped");
        bookings.awaitHoldReleased(booked.sessionId());
        var ended = bookings.session(booked.renter(), booked.sessionId());
        assertThat(ended.get("chargedPaise").asLong())
                .as("the one minute it ran, at 2 paise")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select balance_paise from billing.wallets where user_id = ?",
                        Long.class,
                        booked.renter().id()))
                .isEqualTo(50_000L - 2);

        // The renter hears it didn't finish, and why, with what it cost.
        var told = bookings.awaitNotification(booked.renter().id(), "session-failed");
        assertThat(told.get("body").toString())
                .contains("We stopped it, so you were charged only for the minutes it ran.", "It cost ₹0.02.");

        var timeline = read(get("/admin/v1/sessions/" + booked.sessionId()), admin);
        assertThat(timeline.get("timeline").findValuesAsString("reason")).contains("operator_stopped");
        assertThat(timeline.get("attempts").get(0).get("hostId").asString())
                .isEqualTo(booked.host().id().toString());
        assertThat(timeline.get("chargedPaise").asLong()).isEqualTo(2);
        assertThat(timeline.get("settled").asBoolean()).isTrue();
        mvc.perform(admin.as(reason(post("/admin/v1/sessions/" + booked.sessionId() + "/stop"), "once more please")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/session-not-running"));
        assertThat(audited(booked)).isOne();
    }

    @Test
    void anAdminReadsASessionsLogOnlyWithWhyAndEachReadIsAudited() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var booked = running();
        var path = "/admin/v1/sessions/" + booked.sessionId() + "/log";
        mvc.perform(admin.as(get(path))).andExpect(status().isBadRequest());
        mvc.perform(admin.as(get(path).param("reason", "why"))).andExpect(status().isBadRequest());

        var log = read(get(path).param("reason", "evidence for a chargeback"), admin);
        assertThat(log.get("chunks").isArray()).isTrue();
        assertThat(jdbc.queryForObject(
                        "select reason from identity.audit_log where action = 'session.log_read' and target_id = ?",
                        String.class,
                        booked.sessionId().toString()))
                .isEqualTo("evidence for a chargeback");
        mvc.perform(admin.as(get("/admin/v1/sessions/" + java.util.UUID.randomUUID() + "/log")
                        .param("reason", "evidence for a chargeback")))
                .andExpect(status().isNotFound());
        // A renter never reaches it.
        mvc.perform(get(path)
                        .param("reason", "evidence for a chargeback")
                        .header("Authorization", booked.renter().bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void findsSessionsByRenterHostAndState() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var booked = running();
        var byRenter = read(
                get("/admin/v1/sessions").param("user", booked.renter().id().toString()), admin);
        assertThat(byRenter.get("sessions")).hasSize(1);
        assertThat(byRenter.get("sessions").get(0).get("state").asString()).isEqualTo("RUNNING");
        var byHostAndState = read(
                get("/admin/v1/sessions")
                        .param("host", booked.host().id().toString())
                        .param("state", "running"),
                admin);
        assertThat(byHostAndState.get("sessions").get(0).get("id").asString())
                .isEqualTo(booked.sessionId().toString());
        assertThat(read(get("/admin/v1/sessions").param("id", booked.sessionId().toString()), admin)
                        .get("sessions"))
                .hasSize(1);
        mvc.perform(admin.as(get("/admin/v1/sessions/" + java.util.UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aForceStopAndItsAuditRowRollBackTogether() throws Exception {
        var booked = running();
        transactions.executeWithoutResult(status -> {
            assertThat(forceStops.stop(booked.sessionId(), "rolled back", AuditTrail.Actor.operator("tester")))
                    .isEqualTo(SessionForceStops.Outcome.STOPPING);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject(
                        "select state from sessions.sessions where id = ?", String.class, booked.sessionId()))
                .isEqualTo("RUNNING");
        assertThat(audited(booked)).isZero();
    }
}
