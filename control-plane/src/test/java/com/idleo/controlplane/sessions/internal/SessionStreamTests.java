package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.EventStreams;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One live stream per session (ADR 0019), followed through a fake agent's session to settled. */
@ControlPlaneTest
class SessionStreamTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionStreams streams;

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
    }

    static List<JsonNode> sessions(MvcResult result) throws Exception {
        var sessions = new ArrayList<JsonNode>();
        for (var event : EventStreams.events(result)) {
            if (event.name().equals("session")) {
                sessions.add(JSON.readTree(event.data()));
            }
        }
        return sessions;
    }

    static void await(MvcResult result, Predicate<List<JsonNode>> condition, String what) throws Exception {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.test(sessions(result))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        "the stream never showed " + what + "; it sent " + EventStreams.events(result));
            }
            Thread.sleep(50);
        }
    }

    private MvcResult open(TestUser renter, UUID session) throws Exception {
        return mvc.perform(get("/v1/sessions/" + session + "/events")
                        .header("Authorization", renter.bearer())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andReturn();
    }

    private void report(TestBookings.Booked booked, SessionStatus.Builder status) throws InterruptedException {
        var request = TestAgent.request()
                .setSessionStatus(
                        status.setSessionId(booked.sessionId().toString()).setAttempt(1))
                .build();
        booked.host().stream().send(request);
        booked.host().stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        TestBookings.WAIT);
    }

    @Test
    void theStreamFollowsASessionToSettledAndCloses() throws Exception {
        var booked = bookings.bookAndAccept();
        var envelope = booked.host().awaitStart(TestBookings.WAIT);
        booked.host().ack(envelope);
        var stream = open(booked.renter(), booked.sessionId());
        assertThat(stream.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        await(stream, sessions -> !sessions.isEmpty(), "the session on connect");
        assertThat(sessions(stream).getFirst().get("state").asString()).isEqualTo("PROVISIONING");

        report(
                booked,
                SessionStatus.newBuilder()
                        .setState(SessionState.SESSION_STATE_RUNNING)
                        .setLatestLogSeq(1));
        await(stream, sessions -> sessions.getLast().get("state").asString().equals("RUNNING"), "RUNNING");
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (EventStreams.events(stream).stream()
                .noneMatch(event -> event.name().equals("logs"))) {
            assertThat(System.nanoTime()).as("a logs event").isLessThan(deadline);
            Thread.sleep(50);
        }
        var logs = EventStreams.events(stream).stream()
                .filter(event -> event.name().equals("logs"))
                .findFirst()
                .orElseThrow();
        assertThat(JSON.readTree(logs.data()).get("attempt").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(logs.data()).get("latestSeq").asInt()).isEqualTo(1);

        report(
                booked,
                SessionStatus.newBuilder()
                        .setState(SessionState.SESSION_STATE_FAILED)
                        .setError(gpu.agent.v1.Session.SessionError.newBuilder().setCode("job_failed")));
        await(stream, sessions -> sessions.getLast().get("settled").asBoolean(), "settled");

        var states = sessions(stream).stream()
                .map(session -> session.get("state").asString())
                .toList();
        assertThat(states).containsSubsequence("PROVISIONING", "RUNNING", "FAILED");
        var last = sessions(stream).getLast();
        assertThat(last.get("settled").asBoolean()).isTrue();
        assertThat(last.get("endedAt").isNull()).isFalse();
        deadline = System.nanoTime() + WAIT.toNanos();
        while (streams.openCount() > 0) {
            assertThat(System.nanoTime()).as("the stream closed after settled").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void aChangeWithNoHintStillReachesTheStreamWithinTheReread() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(TestBookings.WAIT));
        var stream = open(booked.renter(), booked.sessionId());
        await(stream, sessions -> !sessions.isEmpty(), "the session on connect");
        var before = sessions(stream).size();

        // As another instance's change would look if its hint were lost: straight in the database, no hint.
        jdbc.update("update sessions.sessions set max_spend_paise = 9999 where id = ?", booked.sessionId());
        var changedAt = System.nanoTime();
        await(
                stream,
                sessions -> sessions.size() > before
                        && sessions.getLast().get("maxSpendPaise").asLong() == 9999,
                "the change");

        assertThat(Duration.ofNanos(System.nanoTime() - changedAt)).isLessThanOrEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void aRenterHasAtMostAFewStreamsAndOnlyOfTheirOwnSessions() throws Exception {
        var booked = bookings.bookAndAccept();
        var opened = new ArrayList<MvcResult>();
        for (int i = 0; i < 3; i++) {
            opened.add(open(booked.renter(), booked.sessionId()));
        }

        mvc.perform(get("/v1/sessions/" + booked.sessionId() + "/events")
                        .header("Authorization", booked.renter().bearer())
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.type")
                        .value("/problems/too-many-streams"));
        var other = TestUsers.signUp(mvc);
        mvc.perform(get("/v1/sessions/" + booked.sessionId() + "/events")
                        .header("Authorization", other.bearer())
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/sessions/" + booked.sessionId() + "/events")).andExpect(status().isUnauthorized());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/v1/sessions/" + booked.sessionId() + "/cancel")
                .header("Authorization", booked.renter().bearer()));
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (streams.openCount() > 0) {
            assertThat(System.nanoTime()).as("all three closed once settled").isLessThan(deadline);
            Thread.sleep(50);
        }
    }
}
