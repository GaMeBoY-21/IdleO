package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import gpu.agent.v1.Session.RunningSession;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Telemetry.Heartbeat;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.sync.RequestBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Session logs (ADR 0019): the agent's progress raises each attempt's newest chunk; the renter reads them in order. */
@ControlPlaneTest
class SessionLogTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StorageProperties storageProperties;

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

    private void heartbeat(ConnectedHost host, UUID session, int attempt, int latestLogSeq) {
        host.stream()
                .send(TestAgent.request()
                        .setHeartbeat(Heartbeat.newBuilder()
                                .addSessions(RunningSession.newBuilder()
                                        .setSessionId(session.toString())
                                        .setAttempt(attempt)
                                        .setState(SessionState.SESSION_STATE_RUNNING)
                                        .setLatestLogSeq(latestLogSeq)))
                        .build());
    }

    private int latestLogSeq(UUID session, int attempt) {
        return jdbc.queryForObject(
                "select latest_log_seq from sessions.attempts where session_id = ? and attempt = ?",
                Integer.class,
                session,
                attempt);
    }

    private void awaitLatestLogSeq(UUID session, int attempt, int wanted) throws InterruptedException {
        var deadline = System.nanoTime() + TestBookings.WAIT.toNanos();
        while (latestLogSeq(session, attempt) != wanted) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("attempt " + attempt + "'s latest log seq never became " + wanted);
            }
            Thread.sleep(50);
        }
    }

    private void putChunk(UUID session, int attempt, int seq, byte[] text) {
        try (var s3 = TestS3.root(storageProperties)) {
            s3.putObject(
                    request -> request.bucket(storageProperties.bucket())
                            .key(StorageLayout.logChunk(session, attempt, seq)),
                    RequestBody.fromBytes(text));
        }
    }

    private JsonNode logs(TestUser renter, UUID session, String query) throws Exception {
        return JSON.readTree(
                mvc.perform(get("/v1/sessions/" + session + "/logs" + query).header("Authorization", renter.bearer()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Test
    void heartbeatsOnlyEverRaiseTheNewestChunkAndOnlyFromTheAttemptsOwnHost() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(TestBookings.WAIT));
        var session = booked.sessionId();

        heartbeat(booked.host(), session, 1, 3);
        awaitLatestLogSeq(session, 1, 3);
        heartbeat(booked.host(), session, 1, 2);
        var stranger = bookings.host();
        heartbeat(stranger, session, 1, 9);
        heartbeat(booked.host(), session, 1, 4);
        awaitLatestLogSeq(session, 1, 4);
        assertThat(latestLogSeq(session, 1))
                .as("never lowered, never another host's")
                .isEqualTo(4);
    }

    @Test
    void logsReadBackInOrderAcrossAttemptsAndAreCompleteOnceSettled() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(TestBookings.WAIT));
        var session = booked.sessionId();
        for (int seq = 1; seq <= 3; seq++) {
            putChunk(session, 1, seq, ("attempt 1 chunk " + seq + "\n").getBytes(StandardCharsets.UTF_8));
        }
        heartbeat(booked.host(), session, 1, 3);
        awaitLatestLogSeq(session, 1, 3);
        // A second attempt, as if the session moved hosts, with a chunk that isn't valid UTF-8.
        jdbc.update("""
                insert into sessions.attempts (session_id, attempt, host_id, gpu_uuids, command_id, minute_index_base,
                                               created_at, latest_log_seq, price_paise_per_min)
                values (?, 2, ?, array['GPU-x'], ?, 0, now(), 2, 2)
                """, session, UUID.randomUUID(), UUID.randomUUID());
        putChunk(session, 2, 1, "attempt 2 chunk 1\n".getBytes(StandardCharsets.UTF_8));
        putChunk(session, 2, 2, new byte[] {'o', 'k', ' ', (byte) 0xff, '\n'});

        var first = logs(booked.renter(), session, "?limit=2");
        assertThat(first.get("chunks").findValuesAsString("text"))
                .containsExactly("attempt 1 chunk 1\n", "attempt 1 chunk 2\n");
        assertThat(first.get("complete").asBoolean()).isFalse();
        var second = logs(
                booked.renter(),
                session,
                "?limit=2&cursor=" + first.get("nextCursor").asString());
        assertThat(second.get("chunks").findValuesAsString("text"))
                .containsExactly("attempt 1 chunk 3\n", "attempt 2 chunk 1\n");
        var third = logs(
                booked.renter(),
                session,
                "?limit=2&cursor=" + second.get("nextCursor").asString());
        assertThat(third.get("chunks").get(0).get("attempt").asInt()).isEqualTo(2);
        assertThat(third.get("chunks").get(0).get("seq").asInt()).isEqualTo(2);
        assertThat(third.get("chunks").get(0).get("text").asString()).isEqualTo("ok �\n");
        assertThat(third.get("complete").asBoolean())
                .as("still running: more may come")
                .isFalse();
        var waiting = logs(
                booked.renter(), session, "?cursor=" + third.get("nextCursor").asString());
        assertThat(waiting.get("chunks")).isEmpty();
        assertThat(waiting.get("nextCursor").asString())
                .isEqualTo(third.get("nextCursor").asString());

        mvc.perform(post("/v1/sessions/" + session + "/cancel")
                        .header("Authorization", booked.renter().bearer()))
                .andExpect(status().isOk());
        var done = logs(
                booked.renter(), session, "?cursor=" + second.get("nextCursor").asString());
        assertThat(done.get("chunks")).hasSize(1);
        assertThat(done.get("complete").asBoolean())
                .as("settled, and nothing left")
                .isTrue();
        assertThat(done.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void onlyTheRenterReadsTheLogs() throws Exception {
        var booked = bookings.bookAndAccept();
        var other = TestUsers.signUp(mvc);
        mvc.perform(get("/v1/sessions/" + booked.sessionId() + "/logs").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/sessions/" + booked.sessionId() + "/logs?cursor=forged")
                        .header("Authorization", booked.renter().bearer()))
                .andExpect(status().isBadRequest());
    }
}
