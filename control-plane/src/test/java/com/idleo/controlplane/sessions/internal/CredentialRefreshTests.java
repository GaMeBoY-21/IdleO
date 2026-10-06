package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.SessionError;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Session.StoragePermission;
import java.time.Duration;
import java.util.HashSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A job outlives its first storage grant (ADR 0017): past half a grant's lifetime (4 seconds in the test profile),
 * the agent gets fresh credentials, minted at delivery, and keeps writing checkpoints with them.
 */
@ControlPlaneTest
class CredentialRefreshTests {

    static final Duration WAIT = TestBookings.WAIT;

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

    @Test
    void aJobThatOutlivesItsGrantGetsFreshOnesAndKeepsWritingCheckpoints() throws Exception {
        var booked = bookings.bookAndAccept();
        var start = booked.host().awaitStart(WAIT);
        booked.host().ack(start);
        var request = TestAgent.request()
                .setSessionStatus(SessionStatus.newBuilder()
                        .setSessionId(booked.sessionId().toString())
                        .setAttempt(1)
                        .setState(SessionState.SESSION_STATE_RUNNING))
                .build();
        booked.host().stream().send(request);
        var startedAt = System.nanoTime();
        var attemptPrefix = "sessions/" + booked.sessionId() + "/attempt-1/";

        var keys = new HashSet<String>();
        keys.add(start.getStartSession().getCredentials().getAttemptGrant().getAccessKeyId());
        int seq = 0;
        // Past the first grant's 4 seconds, with two refreshes on the way.
        while (seq < 2 || System.nanoTime() - startedAt < Duration.ofSeconds(5).toNanos()) {
            var command = booked.host().stream().await(OpenStreamResponse::hasRefreshCredentials, WAIT);
            var refresh = command.getRefreshCredentials();
            assertThat(refresh.getSessionId()).isEqualTo(booked.sessionId().toString());
            assertThat(refresh.getAttempt()).isEqualTo(1);
            var grant = refresh.getCredentials().getAttemptGrant();
            assertThat(grant.getPrefixesList()).containsExactly(attemptPrefix);
            assertThat(grant.getPermission()).isEqualTo(StoragePermission.STORAGE_PERMISSION_READ_WRITE);
            assertThat(grant.getExpiresIn().getSeconds()).isEqualTo(4);
            assertThat(keys.add(grant.getAccessKeyId()))
                    .as("minted fresh for each delivery")
                    .isTrue();
            booked.host().ack(command);
            seq++;
            try (var s3 = TestS3.as(grant)) {
                TestS3.put(s3, attemptPrefix + "checkpoints/%08d/state.bin".formatted(seq), "progress " + seq);
            }
        }

        var minted = jdbc.queryForObject(
                "select grants_minted_at > start_delivered_at from sessions.attempts where session_id = ?",
                Boolean.class,
                booked.sessionId());
        assertThat(minted)
                .as("the attempt remembers when its grants were last minted")
                .isTrue();
    }

    @Test
    void anAttemptThatEndedGetsNoMoreRefreshes() throws Exception {
        var booked = bookings.bookAndAccept();
        var start = booked.host().awaitStart(WAIT);
        booked.host().ack(start);
        for (var state : new SessionState[] {SessionState.SESSION_STATE_RUNNING, SessionState.SESSION_STATE_FAILED}) {
            var status = SessionStatus.newBuilder()
                    .setSessionId(booked.sessionId().toString())
                    .setAttempt(1)
                    .setState(state);
            if (state == SessionState.SESSION_STATE_FAILED) {
                status.setExitCode(137)
                        .setError(
                                SessionError.newBuilder().setCode("oom_killed").setMessage("Out of memory"));
            }
            booked.host().stream()
                    .send(TestAgent.request().setSessionStatus(status).build());
        }
        bookings.awaitState(booked.renter(), booked.sessionId(), "FAILED");

        assertThat(booked.host().stream().receives(OpenStreamResponse::hasRefreshCredentials, Duration.ofSeconds(4)))
                .isFalse();
    }
}
