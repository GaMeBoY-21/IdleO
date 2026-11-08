package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Flag;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestFlags;
import gpu.agent.v1.Session.OfferDeclineReason;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The kill switches on sessions (ADR 0056): {@code bookings.new} and {@code sessions.start} refuse a new booking with a
 * {@code paused} problem; {@code sessions.start} also holds a booking that waits for a host, which starts once it runs
 * again; and neither ever touches a session that's running.
 */
@ControlPlaneTest
class PausedSessionsTests {

    private static final Duration WAIT = TestBookings.WAIT;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.idleo.controlplane.MutableClock clock;

    @LocalGrpcServerPort
    int grpcPort;

    private TestBookings bookings;

    @BeforeEach
    void bookings() {
        bookings = new TestBookings(mvc, context, jdbc, grpcPort);
    }

    @AfterEach
    void runEverythingAgain() {
        clock.reset();
        TestFlags.runAll(context);
        bookings.close();
    }

    private MockHttpServletResponse book(com.idleo.controlplane.TestUsers.TestUser renter) throws Exception {
        return mvc.perform(
                        post("/v1/sessions")
                                .header("Authorization", renter.bearer())
                                .header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"templateId\": \"cuda-smoke-test\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 10000}"))
                .andReturn()
                .getResponse();
    }

    /** Runs the booked session: StartSession acked, RUNNING reported. */
    private void run(TestBookings.Booked booked) throws Exception {
        var envelope = booked.host().awaitStart(WAIT);
        booked.host().ack(envelope);
        var request = TestAgent.request()
                .setSessionStatus(SessionStatus.newBuilder()
                        .setSessionId(booked.sessionId().toString())
                        .setAttempt(envelope.getStartSession().getAttempt())
                        .setState(SessionState.SESSION_STATE_RUNNING))
                .build();
        booked.host().stream().send(request);
        booked.host().stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        WAIT);
    }

    private String state(UUID sessionId) {
        return jdbc.queryForObject("select state from sessions.sessions where id = ?", String.class, sessionId);
    }

    @Test
    @TestFlags.Enforces(Flag.BOOKINGS_NEW)
    void pausedBookingsRefuseANewOneWhileARunningSessionCarriesOn() throws Exception {
        var booked = bookings.bookAndAccept();
        run(booked);

        TestFlags.pause(context, Flag.BOOKINGS_NEW);
        var refused = book(booked.renter());
        assertThat(refused.getStatus()).isEqualTo(503);
        var problem = JSON.readTree(refused.getContentAsString());
        assertThat(problem.get("type").asString()).isEqualTo("/problems/paused");
        assertThat(problem.get("flag").asString()).isEqualTo("bookings.new");
        assertThat(problem.get("detail").asString()).startsWith("Bookings are paused: ");
        assertThat(refused.getHeader("Retry-After")).isNotNull();

        // Its lease is still renewed, and it's still running.
        var renewal = booked.host().stream()
                .await(
                        message -> message.hasLeaseRenew()
                                && message.getLeaseRenew()
                                        .getSessionId()
                                        .equals(booked.sessionId().toString()),
                        WAIT);
        assertThat(renewal.getLeaseRenew().getAttempt()).isEqualTo(1);
        assertThat(state(booked.sessionId())).isEqualTo("RUNNING");

        TestFlags.runAll(context);
        assertThat(book(booked.renter()).getStatus()).isEqualTo(201);
    }

    @Test
    @TestFlags.Enforces(Flag.SESSIONS_START)
    void pausedStartsRefuseANewBookingAndHoldOneWaitingForAHostUntilTheyRunAgain() throws Exception {
        var booked = bookings.bookAndAccept();
        run(booked);

        TestFlags.pause(context, Flag.SESSIONS_START);
        var refused = book(bookings.renter());
        assertThat(refused.getStatus()).isEqualTo(503);
        assertThat(JSON.readTree(refused.getContentAsString()).get("flag").asString())
                .isEqualTo("sessions.start");

        // The host is lost: the session waits for another, held, and none is offered while starts are paused.
        var other = bookings.host();
        booked.host().disconnect();
        leaseLapsed(booked.sessionId());
        org.awaitility.Awaitility.await().atMost(WAIT).until(() -> "REQUESTED".equals(state(booked.sessionId())));
        assertThat(other.nextOffer(Duration.ofSeconds(6)))
                .as("no offer while starts are paused, through a re-match turn")
                .isEmpty();
        assertThat(state(booked.sessionId())).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.reservations where session_id = ? and status = 'active'",
                        Integer.class,
                        booked.sessionId()))
                .as("its hold is kept")
                .isEqualTo(1);

        TestFlags.runAll(context);
        // Another test's session that lost its host may be offered first; this one comes.
        while (true) {
            var offerId = UUID.fromString(bookings.awaitOffer(other).getOfferId());
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select session_id = ? from scheduling.offers where id = ?",
                    Boolean.class,
                    booked.sessionId(),
                    offerId))) {
                break;
            }
            other.reply(offerId.toString(), false, OfferDeclineReason.OFFER_DECLINE_REASON_INSUFFICIENT_RESOURCES);
        }
    }

    /** Ends the lease in the past and keeps it there, past one renewal the lost host may still have had on its way. */
    private void leaseLapsed(UUID sessionId) throws InterruptedException {
        var past = Timestamp.from(Instant.now().minusSeconds(120));
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        var quietSince = System.nanoTime();
        while (System.nanoTime() < deadline) {
            if (jdbc.update(
                            "update sessions.sessions set lease_expires_at = ? where id = ? and lease_expires_at > ?",
                            past,
                            sessionId,
                            past)
                    > 0) {
                quietSince = System.nanoTime();
            } else if (System.nanoTime() - quietSince > Duration.ofSeconds(1).toNanos()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    @Test
    @TestFlags.Enforces(Flag.MAINTENANCE)
    void maintenanceKeepsAgentsRunningSessionsAndMeteringGoingAndLetsTheRenterStop() throws Exception {
        var booked = bookings.bookAndAccept();
        run(booked);

        TestFlags.pause(context, Flag.MAINTENANCE);
        var refused = book(booked.renter());
        assertThat(refused.getStatus()).isEqualTo(503);
        assertThat(JSON.readTree(refused.getContentAsString()).get("type").asString())
                .isEqualTo("/problems/maintenance");
        assertThat(refused.getHeader("Retry-After")).isNotNull();

        // The agent's stream: its lease is renewed, and the minute it reports is metered with both witnesses.
        booked.host().stream()
                .await(
                        message -> message.hasLeaseRenew()
                                && message.getLeaseRenew()
                                        .getSessionId()
                                        .equals(booked.sessionId().toString()),
                        WAIT);
        clock.advance(Duration.ofSeconds(61));
        booked.host().stream()
                .send(TestAgent.request()
                        .setUsageReport(gpu.agent.v1.Session.UsageReport.newBuilder()
                                .setSessionId(booked.sessionId().toString())
                                .setAttempt(1)
                                .setMinuteIndex(0)
                                .setGpuSeconds(60)
                                .setAvgUtilPct(91))
                        .build());
        org.awaitility.Awaitility.await()
                .atMost(WAIT)
                .until(() -> Boolean.TRUE.equals(jdbc.query(
                        "select agent_reported and lease_alive and lease_checked from metering.usage_minutes"
                                + " where session_id = ? and minute_index = 0",
                        rs -> rs.next() ? rs.getBoolean(1) : null,
                        booked.sessionId())));
        assertThat(state(booked.sessionId())).isEqualTo("RUNNING");

        // Stopping only ever costs the renter less, so it's open.
        var stopped = mvc.perform(post("/v1/sessions/" + booked.sessionId() + "/stop")
                        .header("Authorization", booked.renter().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse();
        assertThat(stopped.getStatus()).as(stopped.getContentAsString()).isEqualTo(200);
    }
}
