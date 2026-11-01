package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.RunningSession;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Session.StartSession;
import gpu.agent.v1.Session.StopReason;
import io.grpc.ManagedChannel;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Losing a host (ADR 0016): the lease lapses, or the agent fences itself or reconnects without the attempt;
 * the session is matched again as its next attempt, resuming from its newest complete checkpoint, and two
 * attempts never run at once.
 */
@ControlPlaneTest
class HostLossTests {

    static final Duration WAIT = TestBookings.WAIT;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StorageProperties storage;

    @LocalGrpcServerPort
    int grpcPort;

    private TestBookings bookings;
    private final List<ManagedChannel> channels = new ArrayList<>();

    @BeforeEach
    void bookings() {
        bookings = new TestBookings(mvc, context, jdbc, grpcPort);
    }

    @AfterEach
    void disconnect() {
        bookings.close();
        channels.forEach(ManagedChannel::shutdownNow);
        channels.clear();
    }

    /** Starts the booked session on its host: StartSession acked, RUNNING reported. */
    private StartSession run(TestBookings.Booked booked) throws Exception {
        return run(booked.host(), booked.sessionId());
    }

    private StartSession run(ConnectedHost host, UUID sessionId) throws Exception {
        var envelope = host.awaitStart(WAIT);
        host.ack(envelope);
        var start = envelope.getStartSession();
        report(
                host.stream(),
                sessionId,
                SessionStatus.newBuilder().setAttempt(start.getAttempt()).setState(SessionState.SESSION_STATE_RUNNING));
        return start;
    }

    private static void report(TestAgent.Stream stream, UUID sessionId, SessionStatus.Builder status)
            throws InterruptedException {
        var request = TestAgent.request()
                .setSessionStatus(status.setSessionId(sessionId.toString()))
                .build();
        stream.send(request);
        stream.await(
                message ->
                        message.hasAck() && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                WAIT);
    }

    /** The host's agent connects again, saying it runs {@code running}: a new stream replacing the old. */
    private TestAgent.Stream reconnect(ConnectedHost host, RunningSession... running) throws InterruptedException {
        var channel = host.agent().channel(grpcPort);
        channels.add(channel);
        var stream = TestAgent.open(channel);
        var hello = TestAgent.hello("0.1.0", TestAgent.GPU_A).toBuilder();
        hello.getHelloBuilder().addAllRunningSessions(List.of(running));
        stream.send(hello.build());
        assertThat(stream.next(Duration.ofSeconds(5)).hasWelcome()).isTrue();
        return stream;
    }

    private static RunningSession running(UUID sessionId, int attempt) {
        return RunningSession.newBuilder()
                .setSessionId(sessionId.toString())
                .setAttempt(attempt)
                .setState(SessionState.SESSION_STATE_RUNNING)
                .build();
    }

    /**
     * Moves the session's lease end to {@code expiresAt}, and keeps it there: a host that has just disconnected may
     * still have one renewal on its way, which would write a fresh end over this one. It holds once a whole second
     * passes with no renewal.
     */
    private void leaseEndsAt(UUID sessionId, Instant expiresAt) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        var quietSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            int moved = jdbc.update(
                    "update sessions.sessions set lease_expires_at = ? where id = ? and lease_expires_at > ?",
                    Timestamp.from(expiresAt),
                    sessionId,
                    Timestamp.from(expiresAt));
            if (moved > 0) {
                quietSince = System.nanoTime();
            } else if (System.nanoTime() - quietSince > Duration.ofSeconds(1).toNanos()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private static void writeCheckpoint(StartSession start, int seq, boolean complete) {
        var key = "sessions/%s/attempt-%d/checkpoints/%08d/".formatted(start.getSessionId(), start.getAttempt(), seq);
        try (var s3 = TestS3.as(start.getCredentials().getAttemptGrant())) {
            TestS3.put(s3, key + "state.json", "{\"step\": " + seq + "}");
            if (complete) {
                TestS3.put(s3, key + "_COMPLETE", "{\"files\": [{\"path\": \"state.json\", \"size\": 13}]}");
            }
        }
    }

    private List<String> transitions(UUID sessionId) {
        return jdbc.queryForList(
                "select to_state || ':' || reason from sessions.session_events where session_id = ? order by at, id",
                String.class,
                sessionId);
    }

    private int activeHolds(UUID sessionId) {
        return jdbc.queryForObject(
                "select count(*) from billing.reservations where session_id = ? and status = 'active'",
                Integer.class,
                sessionId);
    }

    @Test
    void aLapsedLeaseMatchesTheSessionAgainResumingFromTheNewestCompleteCheckpoint() throws Exception {
        var booked = bookings.bookAndAccept();
        var first = run(booked);
        writeCheckpoint(first, 1, true);
        report(
                booked.host().stream(),
                booked.sessionId(),
                SessionStatus.newBuilder()
                        .setAttempt(1)
                        .setState(SessionState.SESSION_STATE_RUNNING)
                        .setLatestCheckpointSeq(1));
        // Finished just before the host died, never reported; and one it never finished.
        writeCheckpoint(first, 2, true);
        writeCheckpoint(first, 3, false);
        var hold = jdbc.queryForObject(
                "select id from billing.reservations where session_id = ? and status = 'active'",
                UUID.class,
                booked.sessionId());
        var other = bookings.host();

        booked.host().disconnect();
        leaseEndsAt(booked.sessionId(), Instant.now().minusSeconds(5));
        other.reply(bookings.awaitOffer(other).getOfferId(), true, TestBookings.ACCEPT);
        var envelope = other.awaitStart(WAIT);
        var second = envelope.getStartSession();

        assertThat(second.getAttempt()).isEqualTo(2);
        assertThat(second.getCommandId()).isNotEqualTo(first.getCommandId());
        assertThat(second.getResumeFrom().getPrefix())
                .isEqualTo("sessions/" + booked.sessionId() + "/attempt-1/checkpoints/");
        assertThat(second.getResumeFrom().getSeq()).isEqualTo(2);
        assertThat(second.getMinuteIndexBase()).isGreaterThanOrEqualTo(1);
        assertThat(second.getCredentials().getAttemptGrant().getPrefixesList())
                .containsExactly("sessions/" + booked.sessionId() + "/attempt-2/");
        var checkpoint = "sessions/" + booked.sessionId() + "/attempt-1/checkpoints/00000002/";
        assertThat(second.getCredentials().getReadGrant().getPrefixesList()).containsExactly(checkpoint);
        try (var read = TestS3.as(second.getCredentials().getReadGrant())) {
            assertThat(TestS3.get(read, checkpoint + "state.json")).isEqualTo("{\"step\": 2}");
            assertThatThrownBy(() -> TestS3.get(
                            read, "sessions/" + booked.sessionId() + "/attempt-1/checkpoints/00000001/state.json"))
                    .isInstanceOf(S3Exception.class);
        }
        assertThat(transitions(booked.sessionId()))
                .containsSubsequence(
                        "RUNNING:running",
                        "HOST_LOST:lease_lapsed",
                        "REQUESTED:rematch",
                        "MATCHED:matched",
                        "PROVISIONING:offer_accepted");
        assertThat(jdbc.queryForObject(
                        "select id from billing.reservations where session_id = ? and status = 'active'",
                        UUID.class,
                        booked.sessionId()))
                .as("the same hold, kept")
                .isEqualTo(hold);
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.reservations where session_id = ?",
                        Integer.class,
                        booked.sessionId()))
                .isEqualTo(1);

        // The renter hears it is moving; the machine's owner, that it went offline (ADR 0026).
        assertThat(bookings.awaitNotification(booked.renter().id(), "host-lost").get("title"))
                .isEqualTo("Your session is moving to another GPU");
        var owner = jdbc.queryForObject(
                "select h.owner_user_id from sessions.attempts a join hosts.hosts h on h.id = a.host_id"
                        + " where a.session_id = ? and a.attempt = 1",
                UUID.class,
                booked.sessionId());
        var offline = bookings.awaitNotification(owner, "machine-offline");
        assertThat(offline.get("title").toString()).endsWith("went offline during a session");
        assertThat(offline.get("body").toString())
                .doesNotContain(booked.sessionId().toString());
    }

    @Test
    void aLostAttemptsMinutesAfterItsNewestCompleteCheckpointAreWaivedFromStoragesTime() throws Exception {
        var booked = bookings.bookAndAccept();
        var first = run(booked);
        writeCheckpoint(first, 1, true);
        report(
                booked.host().stream(),
                booked.sessionId(),
                SessionStatus.newBuilder()
                        .setAttempt(1)
                        .setState(SessionState.SESSION_STATE_RUNNING)
                        .setLatestCheckpointSeq(1));
        // Finished just before the host died, never reported: it still counts.
        writeCheckpoint(first, 2, true);
        Instant completedAt;
        try (var s3 = TestS3.root(storage)) {
            completedAt = s3.headObject(request -> request.bucket(storage.bucket())
                            .key("sessions/%s/attempt-1/checkpoints/00000002/_COMPLETE".formatted(booked.sessionId())))
                    .lastModified();
        }

        booked.host().disconnect();
        leaseEndsAt(booked.sessionId(), Instant.now().minusSeconds(5));

        var deadline = System.nanoTime() + WAIT.toNanos();
        java.util.Map<String, Object> waiver = null;
        while (waiver == null && System.nanoTime() < deadline) {
            var rows = jdbc.queryForList(
                    "select checkpoint_at, waived_from, host_id from metering.waivers where session_id = ? and attempt = 1",
                    booked.sessionId());
            waiver = rows.isEmpty() ? null : rows.getFirst();
            Thread.sleep(50);
        }
        assertThat(waiver).as("the lost attempt is waived").isNotNull();
        var checkpointAt = ((Timestamp) waiver.get("checkpoint_at")).toInstant();
        assertThat(checkpointAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .as("when storage finished the newest marker, on storage's clock")
                .isEqualTo(completedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(((Timestamp) waiver.get("waived_from")).toInstant()).isEqualTo(checkpointAt);
        assertThat(waiver.get("host_id")).isEqualTo(booked.host().id());
    }

    @Test
    void theHostIsLostOnlyAfterTheLeaseAndTheTransitAllowanceHavePassed() throws Exception {
        var booked = bookings.bookAndAccept();
        run(booked);
        booked.host().disconnect();
        var expiresAt = Instant.now().plusSeconds(2);
        leaseEndsAt(booked.sessionId(), expiresAt);

        Thread.sleep(2_200);
        var state = bookings.session(booked.renter(), booked.sessionId())
                .get("state")
                .asString();
        var readBy = Instant.now();
        if (readBy.isBefore(expiresAt.plusSeconds(1))) {
            assertThat(state)
                    .as("lease ended, but a renewal could still be reaching the agent")
                    .isEqualTo("RUNNING");
        }

        bookings.awaitState(booked.renter(), booked.sessionId(), "REQUESTED");
        var lostAt = jdbc.queryForObject(
                        "select at from sessions.session_events where session_id = ? and to_state = 'HOST_LOST'",
                        Timestamp.class,
                        booked.sessionId())
                .toInstant();
        assertThat(lostAt).isAfterOrEqualTo(expiresAt.plusSeconds(1));
    }

    @Test
    void aHostThatLostASessionSeesItAsLostAndNeverSeesWhoRentedIt() throws Exception {
        var booked = bookings.bookAndAccept();
        run(booked);
        booked.host().disconnect();
        leaseEndsAt(booked.sessionId(), Instant.now().minusSeconds(5));
        bookings.awaitState(booked.renter(), booked.sessionId(), "REQUESTED");

        var response = mvc.perform(get("/v1/hosts/" + booked.host().id() + "/sessions")
                        .header("Authorization", booked.host().agent().bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].id").value(booked.sessionId().toString()))
                .andExpect(jsonPath("$.sessions[0].outcome").value("lost"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response)
                .doesNotContain(booked.renter().id().toString())
                .doesNotContain("renter")
                .doesNotContain("params")
                .doesNotContain("input");
    }

    @Test
    void aHostThatReconnectsWithoutTheSessionLosesItAtOnceAndMayBeMatchedAgain() throws Exception {
        var booked = bookings.bookAndAccept();
        run(booked);

        var stream = reconnect(booked.host());

        var offer = stream.await(OpenStreamResponse::hasOffer, WAIT);
        assertThat(transitions(booked.sessionId()))
                .containsSubsequence("HOST_LOST:missing_after_reconnect", "REQUESTED:rematch");
        assertThat(offer.getOffer().getSpec().getImage()).contains("@sha256:");
        assertThat(bookings.session(booked.renter(), booked.sessionId())
                        .get("attempt")
                        .asInt())
                .isEqualTo(2);
        assertThat(activeHolds(booked.sessionId())).isEqualTo(1);
    }

    @Test
    void aHostThatReconnectsStillRunningAnAttemptThatMovedOnIsToldToStopIt() throws Exception {
        var booked = bookings.bookAndAccept();
        run(booked);
        report(
                booked.host().stream(),
                booked.sessionId(),
                SessionStatus.newBuilder().setAttempt(1).setState(SessionState.SESSION_STATE_FAILED));
        bookings.awaitState(booked.renter(), booked.sessionId(), "FAILED");

        var stream = reconnect(booked.host(), running(booked.sessionId(), 1));

        var stop = stream.await(OpenStreamResponse::hasStopSession, WAIT).getStopSession();
        assertThat(stop.getSessionId()).isEqualTo(booked.sessionId().toString());
        assertThat(stop.getAttempt()).isEqualTo(1);
        assertThat(stop.getReason()).isEqualTo(StopReason.STOP_REASON_SUPERSEDED);
        assertThat(stop.getCommandId()).isNotBlank();
        // A late report about the old attempt is acked and changes nothing.
        report(
                stream,
                booked.sessionId(),
                SessionStatus.newBuilder()
                        .setAttempt(1)
                        .setState(SessionState.SESSION_STATE_SUCCEEDED)
                        .setExitCode(0));
        assertThat(bookings.session(booked.renter(), booked.sessionId())
                        .get("state")
                        .asString())
                .isEqualTo("FAILED");
    }

    @Test
    void anAgentThatFencedItselfLosesTheHostWithoutWaitingForTheLease() throws Exception {
        var booked = bookings.bookAndAccept();
        run(booked);

        report(
                booked.host().stream(),
                booked.sessionId(),
                SessionStatus.newBuilder().setAttempt(1).setState(SessionState.SESSION_STATE_LEASE_EXPIRED));

        assertThat(transitions(booked.sessionId()))
                .containsSubsequence("HOST_LOST:lease_expired_on_host", "REQUESTED:rematch");
    }

    @Test
    void aSessionThatLosesItsHostThreeTimesFails() throws Exception {
        var booked = bookings.bookAndAccept();
        var host = booked.host();
        run(booked);
        var stream = reconnect(host);
        for (int attempt = 2; attempt <= 3; attempt++) {
            var offer = stream.await(OpenStreamResponse::hasOffer, WAIT).getOffer();
            stream.send(TestAgent.request()
                    .setOfferReply(gpu.agent.v1.Session.OfferReply.newBuilder()
                            .setOfferId(offer.getOfferId())
                            .setAccepted(true))
                    .build());
            var start = stream.await(OpenStreamResponse::hasStartSession, WAIT);
            assertThat(start.getStartSession().getAttempt()).isEqualTo(attempt);
            stream.send(TestAgent.request()
                    .setAck(gpu.agent.v1.AgentGateway.Ack.newBuilder().setAckedMessageId(start.getMessageId()))
                    .build());
            report(
                    stream,
                    booked.sessionId(),
                    SessionStatus.newBuilder().setAttempt(attempt).setState(SessionState.SESSION_STATE_RUNNING));
            stream = reconnect(host);
        }

        var failed = bookings.awaitState(booked.renter(), booked.sessionId(), "FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("host_lost_repeatedly");
        assertThat(failed.get("attempt").asInt()).isEqualTo(3);
        bookings.awaitHoldReleased(booked.sessionId());
    }

    @Test
    void aSessionWaitsForAHostWhenNoneIsFree() throws Exception {
        var booked = bookings.bookAndAccept();
        run(booked);
        booked.host().disconnect();
        leaseEndsAt(booked.sessionId(), Instant.now().minusSeconds(5));
        bookings.awaitState(booked.renter(), booked.sessionId(), "REQUESTED");
        Thread.sleep(1_000);
        assertThat(bookings.session(booked.renter(), booked.sessionId())
                        .get("state")
                        .asString())
                .as("still waiting, not NO_HOST_FREE")
                .isEqualTo("REQUESTED");

        var next = bookings.host();

        assertThat(bookings.awaitOffer(next).getSpec().getImage()).contains("@sha256:");
        assertThat(activeHolds(booked.sessionId())).isEqualTo(1);
    }

    @Test
    void cancellingAStartedSessionTellsItsAgentToStop() throws Exception {
        var booked = bookings.bookAndAccept();
        var envelope = booked.host().awaitStart(WAIT);
        booked.host().ack(envelope);

        mvc.perform(post("/v1/sessions/" + booked.sessionId() + "/cancel")
                        .header("Authorization", booked.renter().bearer()))
                .andExpect(status().isOk());

        var stop = booked.host().stream()
                .await(OpenStreamResponse::hasStopSession, WAIT)
                .getStopSession();
        assertThat(stop.getAttempt()).isEqualTo(1);
        assertThat(stop.getReason()).isEqualTo(StopReason.STOP_REASON_RENTER_REQUESTED);
    }
}
