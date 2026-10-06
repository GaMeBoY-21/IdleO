package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.TestUsers.TestUser;
import gpu.agent.v1.Session.SessionError;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Session.StartSession;
import java.time.Duration;
import java.util.UUID;
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

/** The control plane follows a job from its agent's reports, and trusts storage, not the agent, for files (ADR 0016). */
@ControlPlaneTest
class SessionProgressTests {

    static final Duration WAIT = TestBookings.WAIT;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

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

    /** A booked session whose agent has the StartSession, and acked it. */
    private record Started(TestBookings.Booked booked, StartSession start) {
        ConnectedHost host() {
            return booked.host();
        }

        UUID id() {
            return booked.sessionId();
        }

        TestUser renter() {
            return booked.renter();
        }

        String attemptPrefix() {
            return "sessions/" + id() + "/attempt-" + start.getAttempt() + "/";
        }
    }

    private Started started() throws Exception {
        var booked = bookings.bookAndAccept();
        var envelope = booked.host().awaitStart(WAIT);
        booked.host().ack(envelope);
        return new Started(booked, envelope.getStartSession());
    }

    /** Sends a status as the agent would, and waits for the control plane's ack. */
    private void report(Started started, SessionStatus.Builder status) throws InterruptedException {
        var request = TestAgent.request()
                .setSessionStatus(status.setSessionId(started.id().toString()))
                .build();
        started.host().stream().send(request);
        started.host().stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        WAIT);
    }

    private static SessionStatus.Builder status(SessionState state, int attempt) {
        return SessionStatus.newBuilder().setAttempt(attempt).setState(state);
    }

    /** Writes a checkpoint folder with the attempt's own grant; the marker only if {@code complete}. */
    private static void writeCheckpoint(Started started, int seq, boolean complete) {
        var folder = started.attemptPrefix() + "checkpoints/%08d/".formatted(seq);
        try (var s3 = TestS3.as(started.start().getCredentials().getAttemptGrant())) {
            TestS3.put(s3, folder + "state.json", "{\"step\": " + seq + "}");
            if (complete) {
                TestS3.put(s3, folder + "_COMPLETE", "{\"files\": [{\"path\": \"state.json\", \"size\": 13}]}");
            }
        }
    }

    private static void writeOutputs(Started started) {
        try (var s3 = TestS3.as(started.start().getCredentials().getAttemptGrant())) {
            TestS3.put(s3, started.attemptPrefix() + "outputs/result.txt", "done");
            TestS3.put(s3, started.attemptPrefix() + "outputs/_COMPLETE", "{\"files\": []}");
        }
    }

    private int checkpoints(UUID sessionId) {
        return jdbc.queryForObject(
                "select count(*) from sessions.checkpoints where session_id = ?", Integer.class, sessionId);
    }

    private JsonNode wallet(TestUser renter) throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    @Test
    void aJobRunsCheckpointsAndCompletesOnceItsOutputsAreInStorage() throws Exception {
        var started = started();

        report(started, status(SessionState.SESSION_STATE_PREPARING, 1));
        assertThat(bookings.session(started.renter(), started.id()).get("state").asString())
                .isEqualTo("PROVISIONING");
        report(started, status(SessionState.SESSION_STATE_RUNNING, 1));
        var running = bookings.session(started.renter(), started.id());
        assertThat(running.get("state").asString()).isEqualTo("RUNNING");
        assertThat(running.get("startedAt").isNull()).isFalse();

        writeCheckpoint(started, 1, true);
        report(started, status(SessionState.SESSION_STATE_RUNNING, 1).setLatestCheckpointSeq(1));
        assertThat(jdbc.queryForObject(
                        "select object_key from sessions.checkpoints where session_id = ? and attempt = 1 and step = 1",
                        String.class,
                        started.id()))
                .isEqualTo(started.attemptPrefix() + "checkpoints/00000001/");

        writeOutputs(started);
        report(
                started,
                status(SessionState.SESSION_STATE_SUCCEEDED, 1).setExitCode(0).setLatestCheckpointSeq(1));

        var completed = bookings.awaitState(started.renter(), started.id(), "COMPLETED");
        assertThat(completed.get("events").findValuesAsString("state"))
                .containsSubsequence("PROVISIONING", "RUNNING", "FINISHING", "COMPLETED");
        bookings.awaitHoldReleased(started.id());
        assertThat(wallet(started.renter()).get("availablePaise").asLong())
                .as("nothing charged yet")
                .isEqualTo(50_000);
    }

    @Test
    void aCheckpointWithoutItsMarkerIsNeverRecorded() throws Exception {
        var started = started();
        report(started, status(SessionState.SESSION_STATE_RUNNING, 1));

        writeCheckpoint(started, 1, false);
        report(started, status(SessionState.SESSION_STATE_RUNNING, 1).setLatestCheckpointSeq(1));
        assertThat(checkpoints(started.id())).isZero();

        writeCheckpoint(started, 2, true);
        report(started, status(SessionState.SESSION_STATE_RUNNING, 1).setLatestCheckpointSeq(2));
        assertThat(jdbc.queryForList(
                        "select step from sessions.checkpoints where session_id = ?", Integer.class, started.id()))
                .containsExactly(2);
    }

    @Test
    void aJobThatSucceedsWithoutOutputsFailsAfterTheWait() throws Exception {
        var started = started();
        report(started, status(SessionState.SESSION_STATE_RUNNING, 1));
        report(started, status(SessionState.SESSION_STATE_SUCCEEDED, 1).setExitCode(0));
        assertThat(bookings.session(started.renter(), started.id()).get("state").asString())
                .isEqualTo("FINISHING");

        var failed = bookings.awaitState(started.renter(), started.id(), "FAILED");

        assertThat(failed.get("errorCode").asString()).isEqualTo("outputs_missing");
        bookings.awaitHoldReleased(started.id());
    }

    @Test
    void aFailedJobKeepsItsErrorCodeAndReleasesTheHold() throws Exception {
        var started = started();
        report(started, status(SessionState.SESSION_STATE_RUNNING, 1));
        report(
                started,
                status(SessionState.SESSION_STATE_FAILED, 1)
                        .setExitCode(137)
                        .setError(
                                SessionError.newBuilder().setCode("oom_killed").setMessage("Out of memory")));

        var failed = bookings.awaitState(started.renter(), started.id(), "FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("oom_killed");
        assertThat(failed.get("events").findValuesAsString("reason")).last().isEqualTo("oom_killed");
        bookings.awaitHoldReleased(started.id());
    }

    @Test
    void aNonZeroExitFailsEvenIfReportedAsSucceeded() throws Exception {
        var started = started();
        report(started, status(SessionState.SESSION_STATE_RUNNING, 1));
        writeOutputs(started);
        report(started, status(SessionState.SESSION_STATE_SUCCEEDED, 1).setExitCode(3));

        assertThat(bookings.awaitState(started.renter(), started.id(), "FAILED")
                        .get("errorCode")
                        .asString())
                .isEqualTo("exit_code_3");
    }

    @Test
    void aFailureWhilePreparingFailsTheStart() throws Exception {
        var started = started();
        report(
                started,
                status(SessionState.SESSION_STATE_FAILED, 1)
                        .setError(SessionError.newBuilder().setCode("image_pull_failed")));

        var failed = bookings.awaitState(started.renter(), started.id(), "FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("image_pull_failed");
        assertThat(failed.get("startedAt").isNull()).isTrue();
    }

    @Test
    void aStatusForAnotherAttemptIsAckedAndIgnored() throws Exception {
        var started = started();

        report(started, status(SessionState.SESSION_STATE_RUNNING, 2));

        assertThat(bookings.session(started.renter(), started.id()).get("state").asString())
                .isEqualTo("PROVISIONING");
    }

    @Test
    void aStatusThatBreaksTheProtocolIsNotAcked() throws Exception {
        var started = started();
        var request = TestAgent.request()
                .setSessionStatus(SessionStatus.newBuilder()
                        .setSessionId(started.id().toString())
                        .setAttempt(1)
                        .setState(SessionState.SESSION_STATE_UNSPECIFIED))
                .build();

        started.host().stream().send(request);

        assertThat(started.host().stream()
                        .receives(
                                message -> message.hasAck()
                                        && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                                Duration.ofSeconds(1)))
                .isFalse();
        assertThat(bookings.session(started.renter(), started.id()).get("state").asString())
                .isEqualTo("PROVISIONING");
    }
}
