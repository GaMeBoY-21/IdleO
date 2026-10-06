package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestUsers;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Session.StopReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** A renter stops a running job (ADR 0017): a checkpoint first, then it completes as stopped. */
@ControlPlaneTest
class SessionStopTests {

    static final java.time.Duration WAIT = TestBookings.WAIT;

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

    private void report(TestBookings.Booked booked, SessionState state, int seq) throws InterruptedException {
        var request = TestAgent.request()
                .setSessionStatus(SessionStatus.newBuilder()
                        .setSessionId(booked.sessionId().toString())
                        .setAttempt(1)
                        .setState(state)
                        .setLatestCheckpointSeq(seq))
                .build();
        booked.host().stream().send(request);
        booked.host().stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        WAIT);
    }

    private TestBookings.Booked running() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(WAIT));
        report(booked, SessionState.SESSION_STATE_RUNNING, 0);
        return booked;
    }

    private org.springframework.test.web.servlet.ResultActions stop(TestBookings.Booked booked, String body)
            throws Exception {
        var request = post("/v1/sessions/" + booked.sessionId() + "/stop")
                .header("Authorization", booked.renter().bearer());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    @Test
    void stoppingARunningJobCheckpointsFirstAndCompletesItAsStopped() throws Exception {
        var booked = running();

        stop(booked, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINISHING"));
        stop(booked, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINISHING"));

        var command = booked.host().stream().await(OpenStreamResponse::hasStopSession, WAIT);
        var stop = command.getStopSession();
        assertThat(stop.getReason()).isEqualTo(StopReason.STOP_REASON_RENTER_REQUESTED);
        assertThat(stop.getCheckpointFirst()).isTrue();
        assertThat(stop.getGracePeriod().getSeconds()).isEqualTo(60);
        booked.host().ack(command);
        assertThat(booked.host().stream().receives(OpenStreamResponse::hasStopSession, java.time.Duration.ofSeconds(2)))
                .as("one StopSession for a repeated stop")
                .isFalse();

        // The agent writes its final checkpoint, then stops.
        report(booked, SessionState.SESSION_STATE_STOPPED, 0);

        var completed = bookings.awaitState(booked.renter(), booked.sessionId(), "COMPLETED");
        assertThat(completed.get("events").findValuesAsString("reason"))
                .containsSubsequence("running", "renter_stopped", "stopped");
    }

    @Test
    void aStopWithoutACheckpointTellsTheAgentSo() throws Exception {
        var booked = running();

        stop(booked, "{\"checkpointFirst\": false}").andExpect(status().isOk());

        var stop = booked.host().stream()
                .await(OpenStreamResponse::hasStopSession, WAIT)
                .getStopSession();
        assertThat(stop.getCheckpointFirst()).isFalse();
    }

    @Test
    void aSessionNotYetRunningIsCancelledNotStopped() throws Exception {
        var booked = bookings.bookAndAccept();

        stop(booked, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("/cancel")));
    }

    @Test
    void anotherRenterCantStopIt() throws Exception {
        var booked = running();
        var stranger = TestUsers.signUp(mvc);

        mvc.perform(post("/v1/sessions/" + booked.sessionId() + "/stop").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
    }
}
