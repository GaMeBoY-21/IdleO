package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.SecretScan;
import com.idleo.controlplane.TestAgent;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.StartSession;
import gpu.agent.v1.Session.StoragePermission;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** An accepted offer starts the attempt on the host's agent, with fresh scoped credentials and a lease (ADR 0016). */
@ControlPlaneTest
@ExtendWith(OutputCaptureExtension.class)
class SessionStartTests {

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

    @BeforeEach
    void bookings() {
        bookings = new TestBookings(mvc, context, jdbc, grpcPort);
    }

    @AfterEach
    void disconnect() {
        bookings.close();
    }

    private TestBookings.Booked bookAndAccept() throws Exception {
        return bookings.bookAndAccept();
    }

    @Test
    void theStartSessionCarriesTheAttemptALeaseAndAGrantForItsOwnFolderOnly() throws Exception {
        var booked = bookAndAccept();
        var envelope = booked.host().awaitStart(WAIT);
        var start = envelope.getStartSession();

        assertThat(start.getSessionId()).isEqualTo(booked.sessionId().toString());
        assertThat(start.getAttempt()).isEqualTo(1);
        assertThat(start.getCommandId())
                .isEqualTo(jdbc.queryForObject(
                                "select command_id from sessions.attempts where session_id = ? and attempt = 1",
                                UUID.class,
                                booked.sessionId())
                        .toString());
        assertThat(start.getSpec().getImage()).contains("@sha256:");
        assertThat(start.getLeaseTtl().getSeconds()).isEqualTo(120);
        assertThat(start.hasMinuteIndexBase()).isTrue();
        assertThat(start.getMinuteIndexBase()).isZero();
        assertThat(start.getGpuUuidsList()).containsExactly(TestAgent.GPU_A);
        assertThat(start.hasResumeFrom()).isFalse();

        var grant = start.getCredentials().getAttemptGrant();
        var attemptPrefix = "sessions/" + booked.sessionId() + "/attempt-1/";
        assertThat(grant.getPrefixesList()).containsExactly(attemptPrefix);
        assertThat(grant.getPermission()).isEqualTo(StoragePermission.STORAGE_PERMISSION_READ_WRITE);
        assertThat(grant.getBucket()).isEqualTo(storage.bucket());
        assertThat(grant.getExpiresIn().getSeconds()).isEqualTo(3600);
        assertThat(start.getCredentials().hasReadGrant())
                .as("no input and nothing to resume")
                .isFalse();
        try (var s3 = TestS3.as(grant)) {
            TestS3.put(s3, attemptPrefix + "checkpoints/00000001/state.bin", "progress");
        }

        var lease = jdbc.queryForMap("""
                select s.lease_expires_at, a.start_delivered_at
                from sessions.sessions s join sessions.attempts a on a.session_id = s.id and a.attempt = s.attempt
                where s.id = ?
                """, booked.sessionId());
        var deliveredAt = ((Timestamp) lease.get("start_delivered_at")).toInstant();
        assertThat(((Timestamp) lease.get("lease_expires_at")).toInstant())
                .as("send time plus the lease")
                .isEqualTo(deliveredAt.plus(Duration.ofMinutes(2)));

        booked.host().ack(envelope);
        awaitCommandStatus(booked.sessionId(), "acked");
    }

    @Test
    void aRedeliveryKeepsTheCommandIdAndMintsFreshCredentials() throws Exception {
        var booked = bookAndAccept();
        var first = booked.host().awaitStart(WAIT);
        // No ack: the queue resends it.
        var second = booked.host().awaitStart(WAIT);

        assertThat(second.getMessageId()).isEqualTo(first.getMessageId());
        assertThat(second.getStartSession().getCommandId())
                .isEqualTo(first.getStartSession().getCommandId());
        assertThat(second.getStartSession().getCredentials().getAttemptGrant().getAccessKeyId())
                .as("fresh grants on every delivery")
                .isNotEqualTo(first.getStartSession()
                        .getCredentials()
                        .getAttemptGrant()
                        .getAccessKeyId());
        booked.host().ack(second);
        awaitCommandStatus(booked.sessionId(), "acked");
    }

    @Test
    void aRepeatedAcceptStartsTheAttemptOnce() throws Exception {
        var booked = bookAndAccept();
        var offerId = jdbc.queryForObject(
                "select id from scheduling.offers where session_id = ?", UUID.class, booked.sessionId());
        // The agent resends its reply, as it does until acked.
        booked.host().reply(offerId.toString(), true, TestBookings.ACCEPT);
        booked.host().ack(booked.host().awaitStart(WAIT));
        awaitCommandStatus(booked.sessionId(), "acked");

        assertThat(jdbc.queryForObject(
                        "select count(*) from sessions.attempts where session_id = ?",
                        Integer.class,
                        booked.sessionId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from agentgateway.agent_commands where session_id = ? and type = 'start_session'",
                        Integer.class,
                        booked.sessionId()))
                .isEqualTo(1);
    }

    @Test
    void aStartSessionWhoseSessionWasCancelledIsWithdrawnNotSent() throws Exception {
        var booked = bookAndAccept();
        booked.host().ack(booked.host().awaitStart(WAIT));
        awaitCommandStatus(booked.sessionId(), "acked");
        var start = parseStart(booked.sessionId());
        mvc.perform(post("/v1/sessions/" + booked.sessionId() + "/cancel")
                        .header("Authorization", booked.renter().bearer()))
                .andExpect(status().isOk());

        var credentials = context.getBean(SessionStarts.class);
        assertThat(credentials.mint(booked.host().id(), start)).isEmpty();
        assertThat(credentials.mint(UUID.randomUUID(), start))
                .as("another host")
                .isEmpty();
    }

    @Test
    void noStorageCredentialIsEverWrittenToATableOrALog(CapturedOutput output) throws Exception {
        var booked = bookAndAccept();
        var first = booked.host().awaitStart(WAIT).getStartSession();
        var second = booked.host().awaitStart(WAIT);
        booked.host().ack(second);
        awaitCommandStatus(booked.sessionId(), "acked");

        var secrets = new ArrayList<String>();
        for (var start : List.of(first, second.getStartSession())) {
            var grant = start.getCredentials().getAttemptGrant();
            secrets.addAll(List.of(grant.getAccessKeyId(), grant.getSecretAccessKey(), grant.getSessionToken()));
        }
        assertThat(secrets).allSatisfy(secret -> assertThat(secret).hasSizeGreaterThan(8));

        assertThat(SecretScan.tablesContaining(jdbc, secrets.toArray(String[]::new)))
                .isEmpty();
        assertThat(output.getAll()).doesNotContain(secrets);
        // The queued command itself is there, without credentials.
        assertThat(parseStart(booked.sessionId()).hasCredentials()).isFalse();
    }

    private StartSession parseStart(UUID sessionId) throws Exception {
        var payload = jdbc.queryForObject(
                "select payload from agentgateway.agent_commands where session_id = ? and type = 'start_session'",
                byte[].class,
                sessionId);
        return OpenStreamResponse.parseFrom(payload).getStartSession();
    }

    private void awaitCommandStatus(UUID sessionId, String wanted) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        String status;
        do {
            status = jdbc.queryForObject(
                    "select status from agentgateway.agent_commands where session_id = ? and type = 'start_session'",
                    String.class,
                    sessionId);
            if (wanted.equals(status)) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("the StartSession is " + status + ", not " + wanted);
    }
}
