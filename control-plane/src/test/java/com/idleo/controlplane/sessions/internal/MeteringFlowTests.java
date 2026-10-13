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
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Session.UsageReport;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** A real session's minutes reach metering with both witnesses (ADR 0017), through the agent stream. */
@ControlPlaneTest
class MeteringFlowTests {

    static final Duration WAIT = TestBookings.WAIT;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @LocalGrpcServerPort
    int grpcPort;

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

    private void send(TestBookings.Booked booked, gpu.agent.v1.AgentGateway.OpenStreamRequest request)
            throws InterruptedException {
        booked.host().stream().send(request);
        booked.host().stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        WAIT);
    }

    @Test
    void aMinuteTheAgentReportedInsideTheLeaseHasBothWitnesses() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(WAIT));
        send(
                booked,
                TestAgent.request()
                        .setSessionStatus(SessionStatus.newBuilder()
                                .setSessionId(booked.sessionId().toString())
                                .setAttempt(1)
                                .setState(SessionState.SESSION_STATE_RUNNING))
                        .build());
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (jdbc.queryForObject(
                                "select count(*) from metering.metered_sessions where session_id = ?",
                                Integer.class,
                                booked.sessionId())
                        == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        clock.advance(Duration.ofSeconds(61));
        send(
                booked,
                TestAgent.request()
                        .setUsageReport(UsageReport.newBuilder()
                                .setSessionId(booked.sessionId().toString())
                                .setAttempt(1)
                                .setMinuteIndex(0)
                                .setGpuSeconds(60)
                                .setAvgUtilPct(93))
                        .build());

        Map<String, Object> minute = Map.of();
        deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var rows = jdbc.queryForList(
                    "select agent_reported, lease_alive, lease_checked, gpu_seconds from metering.usage_minutes"
                            + " where session_id = ? and minute_index = 0",
                    booked.sessionId());
            if (!rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst().get("lease_checked"))) {
                minute = rows.getFirst();
                break;
            }
            Thread.sleep(50);
        }
        assertThat(minute)
                .containsEntry("agent_reported", true)
                .containsEntry("lease_alive", true)
                .containsEntry("gpu_seconds", 60);
        var view = bookings.session(booked.renter(), booked.sessionId());
        assertThat(view.get("accruedPaise").asLong())
                .as("one minute at 2 paise")
                .isEqualTo(2);
        assertThat(view.get("chargedPaise").asLong()).isZero();

        // The job ends; with no outputs it fails, and the session's usage is settled and closed.
        send(
                booked,
                TestAgent.request()
                        .setSessionStatus(SessionStatus.newBuilder()
                                .setSessionId(booked.sessionId().toString())
                                .setAttempt(1)
                                .setState(SessionState.SESSION_STATE_FAILED))
                        .build());
        bookings.awaitHoldReleased(booked.sessionId());
        var ended = bookings.session(booked.renter(), booked.sessionId());
        assertThat(ended.get("chargedPaise").asLong()).isEqualTo(2);
        assertThat(ended.get("accruedPaise").asLong()).isZero();
        assertThat(jdbc.queryForObject(
                        "select balance_paise from billing.wallets where user_id = ?",
                        Long.class,
                        booked.renter().id()))
                .isEqualTo(50_000L - 2);

        // Its end reaches the renter by the real events, with what it cost (ADR 0026).
        var told = bookings.awaitNotification(booked.renter().id(), "session-failed");
        assertThat(told.get("body").toString()).contains("It cost ₹0.02.");
        assertThat(told.get("link")).isEqualTo("/session/?id=" + booked.sessionId());

        // The same real events let its renter rate it and report it, and a refund comes off its charge (ADR 0028):
        // 1 of the 2 paise, all of it from the platform's commission, so the host keeps its 1.
        var renter = booked.renter().bearer();
        var rating = "/v1/sessions/" + booked.sessionId() + "/rating";
        deadline = System.nanoTime() + WAIT.toNanos();
        while (mvc.perform(get(rating).header("Authorization", renter))
                                .andReturn()
                                .getResponse()
                                .getStatus()
                        != 200
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        mvc.perform(post(rating)
                        .header("Authorization", renter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":2}"))
                .andExpect(status().isOk());
        var report = JsonMapper.builder()
                .build()
                .readTree(mvc.perform(post("/v1/disputes")
                                .header("Authorization", renter)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sessionId\":\"" + booked.sessionId() + "\",\"reason\":\"failed\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString();
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(admin.as(post("/admin/v1/disputes/" + report + "/refund"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":1,\"reason\":\"It failed; half of it back.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispute.commissionRefundPaise").value(1))
                .andExpect(jsonPath("$.dispute.hostRefundPaise").value(0));
        assertThat(bookings.session(booked.renter(), booked.sessionId())
                        .get("chargedPaise")
                        .asLong())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select balance_paise from billing.wallets where user_id = ?",
                        Long.class,
                        booked.renter().id()))
                .isEqualTo(50_000L - 1);
    }
}
