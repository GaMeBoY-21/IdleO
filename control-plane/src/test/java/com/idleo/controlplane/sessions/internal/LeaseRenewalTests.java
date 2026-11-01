package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The control plane's side of leases (ADR 0016): renewed while the attempt is live and its host is heard from. */
@ControlPlaneTest
class LeaseRenewalTests {

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

    /** The session's lease expiry, once the delivery that set it has committed. */
    private Instant leaseExpiresAt(UUID sessionId) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var expiresAt = jdbc.queryForObject(
                    "select lease_expires_at from sessions.sessions where id = ?", java.sql.Timestamp.class, sessionId);
            if (expiresAt != null) {
                return expiresAt.toInstant();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("session " + sessionId + " never got a lease");
    }

    /**
     * The session's lease expiry once a renewal has moved it past {@code after}: a renewal writes it just after its
     * send, and one may already be counted in {@code after}.
     */
    private Instant leaseExpiresAfter(UUID sessionId, Instant after) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        var expiresAt = leaseExpiresAt(sessionId);
        while (!expiresAt.isAfter(after) && System.nanoTime() < deadline) {
            Thread.sleep(20);
            expiresAt = leaseExpiresAt(sessionId);
        }
        return expiresAt;
    }

    private static boolean renewalFor(OpenStreamResponse message, UUID sessionId) {
        return message.hasLeaseRenew() && message.getLeaseRenew().getSessionId().equals(sessionId.toString());
    }

    @Test
    void aStartedAttemptsLeaseIsRenewedAndItsExpiryMovesWithEachRenewal() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(WAIT));
        var first = leaseExpiresAt(booked.sessionId());

        var renewal = booked.host().stream().await(message -> renewalFor(message, booked.sessionId()), WAIT);

        assertThat(renewal.getLeaseRenew().getAttempt()).isEqualTo(1);
        assertThat(renewal.getLeaseRenew().getLeaseTtl().getSeconds()).isEqualTo(120);
        assertThat(renewal.getMessageId()).isNotBlank();
        assertThat(leaseExpiresAfter(booked.sessionId(), first)).isAfter(first);
    }

    @Test
    void aHostThatStopsHeartbeatingGetsNoRenewals() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(WAIT));
        booked.host().stream().await(message -> renewalFor(message, booked.sessionId()), WAIT);

        booked.host().heartbeats().interrupt();
        // Presence lives 2 seconds in tests; after it, the stream no longer counts as live.
        Thread.sleep(2_500);
        var lastExpiry = leaseExpiresAt(booked.sessionId());
        booked.host().stream().clear();

        assertThat(booked.host().stream()
                        .receives(message -> renewalFor(message, booked.sessionId()), Duration.ofSeconds(2)))
                .isFalse();
        assertThat(leaseExpiresAt(booked.sessionId())).isEqualTo(lastExpiry);
    }

    @Test
    void aCancelledSessionIsNoLongerRenewed() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(WAIT));
        booked.host().stream().await(message -> renewalFor(message, booked.sessionId()), WAIT);

        mvc.perform(post("/v1/sessions/" + booked.sessionId() + "/cancel")
                        .header("Authorization", booked.renter().bearer()))
                .andExpect(status().isOk());
        // A renewal already on its way may still arrive; after that, none.
        Thread.sleep(1_000);
        booked.host().stream().clear();

        assertThat(booked.host().stream()
                        .receives(message -> renewalFor(message, booked.sessionId()), Duration.ofSeconds(2)))
                .isFalse();
    }
}
