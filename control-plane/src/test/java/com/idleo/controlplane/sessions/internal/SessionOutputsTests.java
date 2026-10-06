package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestUsers;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** A completed session's outputs, for its renter only (ADR 0016). */
@ControlPlaneTest
class SessionOutputsTests {

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

    private void report(TestBookings.Booked booked, SessionStatus.Builder status) throws InterruptedException {
        var request = TestAgent.request()
                .setSessionStatus(status.setSessionId(booked.sessionId().toString()))
                .build();
        booked.host().stream().send(request);
        booked.host().stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        TestBookings.WAIT);
    }

    @Test
    void theRenterDownloadsACompletedSessionsOutputsAndNobodyElseCan() throws Exception {
        var booked = bookings.bookAndAccept();
        var envelope = booked.host().awaitStart(TestBookings.WAIT);
        booked.host().ack(envelope);
        var outputs = "sessions/" + booked.sessionId() + "/attempt-1/outputs/";
        report(booked, SessionStatus.newBuilder().setAttempt(1).setState(SessionState.SESSION_STATE_RUNNING));
        mvc.perform(get("/v1/sessions/" + booked.sessionId() + "/outputs")
                        .header("Authorization", booked.renter().bearer()))
                .andExpect(status().isConflict());

        try (var s3 = TestS3.as(envelope.getStartSession().getCredentials().getAttemptGrant())) {
            TestS3.put(s3, outputs + "result.txt", "attempt=1");
            TestS3.put(s3, outputs + "logs/summary.txt", "ok");
            TestS3.put(s3, outputs + "_COMPLETE", "{\"files\": []}");
        }
        report(
                booked,
                SessionStatus.newBuilder()
                        .setAttempt(1)
                        .setState(SessionState.SESSION_STATE_SUCCEEDED)
                        .setExitCode(0));
        bookings.awaitState(booked.renter(), booked.sessionId(), "COMPLETED");

        var body = mvc.perform(get("/v1/sessions/" + booked.sessionId() + "/outputs")
                        .header("Authorization", booked.renter().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.files.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var files = JSON.readTree(body).get("files");
        assertThat(files.findValuesAsString("path")).containsExactlyInAnyOrder("result.txt", "logs/summary.txt");
        String url = null;
        for (var file : files) {
            if (file.get("path").asString().equals("result.txt")) {
                url = file.get("url").asString();
                assertThat(file.get("sizeBytes").asLong()).isEqualTo(9);
            }
        }
        try (var http = HttpClient.newHttpClient()) {
            var download =
                    http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo("attempt=1");
        }

        var stranger = TestUsers.signUp(mvc);
        mvc.perform(get("/v1/sessions/" + booked.sessionId() + "/outputs").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/sessions/" + UUID.randomUUID() + "/outputs")
                        .header("Authorization", booked.renter().bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/sessions/" + booked.sessionId() + "/outputs")).andExpect(status().isUnauthorized());
    }
}
