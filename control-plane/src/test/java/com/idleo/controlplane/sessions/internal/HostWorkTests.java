package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts;
import com.idleo.controlplane.TestUsers;
import gpu.agent.v1.AgentGateway.OpenStreamRequest;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Session.UsageReport;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a host sees of its work (ADR 0023): the session running now with what it has earned so far, and the sessions it
 * ran; every figure matches the ledger once settled, and no renter appears.
 */
@ControlPlaneTest
class HostWorkTests {

    private static final Duration WAIT = TestBookings.WAIT;
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    private void send(TestBookings.Booked booked, OpenStreamRequest request) throws InterruptedException {
        booked.host().stream().send(request);
        booked.host().stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        WAIT);
    }

    private JsonNode json(String path, String bearer) throws Exception {
        return JSON.readTree(mvc.perform(get(path).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private long ledgerNet(java.util.UUID hostId) {
        return jdbc.queryForObject("""
                        select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)
                        from billing.ledger_entries where account = 'host_payable' and owner_id = ?
                        """, Long.class, hostId);
    }

    @Test
    void aRunningSessionShowsWhatTheHostEarnedSoFarAndLaterMatchesTheLedger() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 50);
        var renter = bookings.renter();
        var sessionId = bookings.book(renter, 60, 10_000);
        host.reply(host.awaitOffer(WAIT).getOfferId(), true, TestBookings.ACCEPT);
        var booked = new TestBookings.Booked(host, renter, sessionId);
        host.ack(host.awaitStart(WAIT));
        send(
                booked,
                TestAgent.request()
                        .setSessionStatus(SessionStatus.newBuilder()
                                .setSessionId(sessionId.toString())
                                .setAttempt(1)
                                .setState(SessionState.SESSION_STATE_RUNNING))
                        .build());
        awaitRows("select count(*) from metering.metered_sessions where session_id = ?", sessionId);
        var path = "/v1/hosts/" + host.id();
        assertThat(json(path, host.agent().bearer)
                        .get("currentSession")
                        .get("earnedSoFarPaise")
                        .asLong())
                .as("nothing is accrued before the first minute")
                .isZero();

        clock.advance(Duration.ofSeconds(61));
        send(
                booked,
                TestAgent.request()
                        .setUsageReport(UsageReport.newBuilder()
                                .setSessionId(sessionId.toString())
                                .setAttempt(1)
                                .setMinuteIndex(0)
                                .setGpuSeconds(60)
                                .setAvgUtilPct(90))
                        .build());
        var deadline = System.nanoTime() + WAIT.toNanos();
        JsonNode current;
        do {
            current = json(path, host.agent().bearer).get("currentSession");
            Thread.sleep(50);
        } while (current.get("earnedSoFarPaise").asLong() == 0 && System.nanoTime() < deadline);

        // One minute at 50 paise: the host keeps 80%.
        assertThat(current.get("earnedSoFarPaise").asLong()).isEqualTo(40);
        assertThat(current.get("serviceName").asString()).isNotBlank();
        assertThat(current.get("runningLabel").asString()).isNotBlank();
        assertThat(current.get("startedAt").asString()).isNotBlank();
        assertThat(current.toString()).doesNotContain(renter.id().toString());

        send(
                booked,
                TestAgent.request()
                        .setSessionStatus(SessionStatus.newBuilder()
                                .setSessionId(sessionId.toString())
                                .setAttempt(1)
                                .setState(SessionState.SESSION_STATE_FAILED))
                        .build());
        bookings.awaitHoldReleased(sessionId);

        assertThat(ledgerNet(host.id())).isEqualTo(40);
        assertThat(json(path, host.agent().bearer).get("currentSession").isNull())
                .as("the host is idle again")
                .isTrue();
        var ran = json(path + "/sessions", host.agent().bearer);
        assertThat(ran.get("sessions")).hasSize(1);
        var row = ran.get("sessions").get(0);
        assertThat(row.get("earnedPaise").asLong()).isEqualTo(ledgerNet(host.id()));
        assertThat(row.get("givenBackPaise").asLong()).isZero();
        assertThat(row.get("minutes").asInt()).isPositive();
        assertThat(row.get("outcome").asString()).isEqualTo("failed");
        assertThat(ran.toString()).doesNotContain(renter.id().toString()).doesNotContain("renter");

        // The owner's earnings across machines say the same, with the service and minutes behind the amount.
        var all = json("/v1/earnings", host.agent().bearer);
        assertThat(all.get("totalPaise").asLong()).isEqualTo(ledgerNet(host.id()));
        var entry = all.get("history").get("entries").get(0);
        assertThat(entry.get("amountPaise").asLong()).isEqualTo(40);
        assertThat(entry.get("minutes").asInt()).isEqualTo(1);
        assertThat(entry.get("serviceName").asString())
                .isEqualTo(row.get("serviceName").asString());
        assertThat(all.get("minutesRentedThisWeek").asLong()).isEqualTo(1);
        assertThat(all.toString()).doesNotContain(renter.id().toString());
        host.disconnect();
    }

    @Test
    void theListPagesNewestFirstAndAnotherUsersHostIsNotFound() throws Exception {
        var host = TestHosts.ready(mvc, grpcPort, jdbc, 2);
        var owner = host.agent().bearer;
        // Three attempts that ran, seeded as the sessions module would leave them.
        var renter = bookings.renter();
        for (int i = 0; i < 3; i++) {
            var sessionId = java.util.UUID.randomUUID();
            seedFinished(
                    sessionId,
                    renter.id(),
                    host.id(),
                    java.time.Instant.parse("2026-10-01T10:00:00Z").plusSeconds(i * 3_600L));
        }

        var first = json("/v1/hosts/" + host.id() + "/sessions?limit=2", owner);
        assertThat(first.get("sessions")).hasSize(2);
        assertThat(first.get("nextCursor").isNull()).isFalse();
        var second = json(
                "/v1/hosts/" + host.id() + "/sessions?limit=2&cursor="
                        + first.get("nextCursor").asString(),
                owner);
        assertThat(second.get("sessions")).hasSize(1);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertThat(first.get("sessions").get(0).get("startedAt").asString())
                .isGreaterThan(first.get("sessions").get(1).get("startedAt").asString());
        assertThat(first.get("sessions").get(1).get("startedAt").asString())
                .isGreaterThan(second.get("sessions").get(0).get("startedAt").asString());
        assertThat(first.get("sessions").get(0).get("minutes").asInt()).isEqualTo(30);
        assertThat(first.get("sessions").get(0).get("outcome").asString()).isEqualTo("completed");

        var stranger = TestUsers.signUp(mvc);
        mvc.perform(get("/v1/hosts/" + host.id() + "/sessions").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/hosts/" + host.id() + "/sessions?limit=0").header("Authorization", owner))
                .andExpect(status().isBadRequest());
        host.disconnect();
    }

    private void seedFinished(
            java.util.UUID sessionId, java.util.UUID renterId, java.util.UUID hostId, java.time.Instant startedAt) {
        var at = java.sql.Timestamp.from(startedAt);
        jdbc.update("""
                insert into sessions.sessions (id, renter_id, host_id, type, state, attempt, template_id, image_digest,
                    vram_needed_mb, max_price_paise_per_min, price_paise_per_min, max_spend_paise, max_runtime_seconds,
                    idempotency_key, request_hash, state_changed_at, version, created_at)
                values (?, ?, ?, 'batch', 'COMPLETED', 1, 'cuda-smoke-test', 'img@sha256:abc', 1000, 5, 2, 1000, 3600,
                    ?, 'hash', ?, 1, ?)
                """, sessionId, renterId, hostId, sessionId.toString(), at, at);
        jdbc.update(
                """
                insert into sessions.attempts (session_id, attempt, host_id, gpu_uuids, command_id, minute_index_base,
                    created_at, running_at, ended_at, price_paise_per_min)
                values (?, 1, ?, array['GPU-1'], ?, 0, ?, ?, ?, 2)
                """,
                sessionId,
                hostId,
                java.util.UUID.randomUUID(),
                at,
                at,
                java.sql.Timestamp.from(startedAt.plusSeconds(30 * 60 - 20)));
    }

    private void awaitRows(String sql, Object... args) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (jdbc.queryForObject(sql, Integer.class, args) == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }
}
