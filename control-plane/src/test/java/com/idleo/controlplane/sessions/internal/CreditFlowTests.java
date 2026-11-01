package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAgent;
import com.idleo.controlplane.TestHosts.ConnectedHost;
import com.idleo.controlplane.agentgateway.AgentStreams;
import com.idleo.controlplane.billing.CreditReservations;
import gpu.agent.v1.AgentGateway.OpenStreamRequest;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.SessionState;
import gpu.agent.v1.Session.SessionStatus;
import gpu.agent.v1.Session.StopReason;
import gpu.agent.v1.Session.UsageReport;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credit stays ahead of the job (ADR 0017): a lease is renewed only while the hold covers it, and a session that
 * runs out of credit or reaches its spending cap is stopped with a checkpoint first, then charged exactly what it
 * used. The test clock stands in for the minutes passing.
 */
@ControlPlaneTest
class CreditFlowTests {

    static final Duration WAIT = TestBookings.WAIT;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CreditReservations reservations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    LeaseRenewals renewals;

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
        clock.reset();
    }

    private record Running(ConnectedHost host, TestBookings.Booked booked) {
        UUID id() {
            return booked.sessionId();
        }
    }

    /** A session at 2 paise a minute, booked by a renter with {@code credit}, running on its agent. */
    private Running running(long credit, long maxSpend) throws Exception {
        var host = bookings.host();
        var renter = bookings.renter(credit);
        var sessionId = bookings.book(renter, 2, maxSpend);
        host.reply(bookings.awaitOffer(host).getOfferId(), true, TestBookings.ACCEPT);
        var booked = new TestBookings.Booked(host, renter, sessionId);
        host.ack(host.awaitStart(WAIT));
        status(booked, SessionState.SESSION_STATE_RUNNING, 0);
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (jdbc.queryForObject(
                                "select count(*) from metering.metered_sessions where session_id = ?",
                                Integer.class,
                                sessionId)
                        == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        var running = new Running(host, booked);
        // Its first renewal, as one comes every 30 seconds: in minute 0 for the clock's purposes.
        renew(running);
        return running;
    }

    /**
     * A renewal round, once the host has heartbeated since the clock last moved: renewals go only to a host heard
     * from within the presence window, on the control plane's clock.
     */
    private void renew(Running running) throws InterruptedException {
        var streams = context.getBean(AgentStreams.class);
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!streams.liveHere().contains(running.host().id())) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("host " + running.host().id() + " never heartbeated on the moved clock");
            }
            Thread.sleep(20);
        }
        renewals.renew();
    }

    private void send(TestBookings.Booked booked, OpenStreamRequest request) throws InterruptedException {
        booked.host().stream().send(request);
        booked.host().stream()
                .await(
                        message -> message.hasAck()
                                && message.getAck().getAckedMessageId().equals(request.getMessageId()),
                        WAIT);
    }

    private void status(TestBookings.Booked booked, SessionState state, int checkpointSeq) throws InterruptedException {
        send(
                booked,
                TestAgent.request()
                        .setSessionStatus(SessionStatus.newBuilder()
                                .setSessionId(booked.sessionId().toString())
                                .setAttempt(1)
                                .setState(state)
                                .setLatestCheckpointSeq(checkpointSeq))
                        .build());
    }

    private void minute(TestBookings.Booked booked, int index, int seconds) throws InterruptedException {
        send(
                booked,
                TestAgent.request()
                        .setUsageReport(UsageReport.newBuilder()
                                .setSessionId(booked.sessionId().toString())
                                .setAttempt(1)
                                .setMinuteIndex(index)
                                .setGpuSeconds(seconds)
                                .setAvgUtilPct(90))
                        .build());
    }

    /** Waits until metering has decided minute {@code index} with both witnesses. */
    private void decided(UUID sessionId, int index) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select coalesce(bool_and(agent_reported and lease_alive), false) from metering.usage_minutes"
                            + " where session_id = ? and minute_index = ?",
                    Boolean.class,
                    sessionId,
                    index))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("minute " + index + " of " + sessionId + " was never decided");
    }

    /**
     * The agent runs one more whole minute: the clock moves on, the agent reports it, metering decides it, and the
     * lease is renewed in the new minute if the credit covers it. Metering decides a minute only on a tick after the
     * clock moved past it, and checks the credit in that same transaction.
     */
    private void runMinute(Running running, int index) throws InterruptedException {
        clock.advance(Duration.ofSeconds(60));
        minute(running.booked(), index, 60);
        decided(running.id(), index);
        renew(running);
    }

    /**
     * Whether metering has stopped the session for its credit, as it decided on the tick that decided the last minute;
     * if it has, the agent is told.
     */
    private boolean stoppedForCredit(Running running) throws InterruptedException {
        var stoppedFor = jdbc.queryForObject(
                "select stopped_for from metering.metered_sessions where session_id = ?", String.class, running.id());
        if (stoppedFor == null) {
            return false;
        }
        running.host().stream().await(OpenStreamResponse::hasStopSession, WAIT);
        return true;
    }

    private long balance(UUID renterId) {
        return jdbc.queryForObject("select balance_paise from billing.wallets where user_id = ?", Long.class, renterId);
    }

    /**
     * After the stop, the agent uses its grace period: it finishes the minute in progress, then runs on until its
     * last lease, bought while the credit covered it, is about to end; then it reports STOPPED. Returns how many
     * minutes it ran in all.
     */
    private int runOutTheLease(Running running, int inProgress) throws Exception {
        var runningAt = jdbc.queryForObject(
                        "select running_at from sessions.attempts where session_id = ? and attempt = 1",
                        java.sql.Timestamp.class,
                        running.id())
                .toInstant();
        var coveredUntil = jdbc.queryForObject(
                        "select max(covered_until) from sessions.lease_coverage where session_id = ?",
                        java.sql.Timestamp.class,
                        running.id())
                .toInstant();
        clock.advance(Duration.between(clock.instant(), runningAt.plus(Duration.ofMinutes(inProgress + 1))));
        status(running.booked(), SessionState.SESSION_STATE_RUNNING, 1);
        minute(running.booked(), inProgress, 60);
        int minutes = inProgress + 1;
        var stopAt = coveredUntil.minusMillis(300);
        var nextMinuteStart = runningAt.plus(Duration.ofMinutes(inProgress + 1));
        if (stopAt.isAfter(nextMinuteStart.plusSeconds(1))) {
            clock.advance(Duration.between(clock.instant(), stopAt));
            int seconds =
                    (int) Math.min(60, Duration.between(nextMinuteStart, stopAt).toSeconds() + 1);
            minute(running.booked(), inProgress + 1, seconds);
            minutes++;
        }
        status(running.booked(), SessionState.SESSION_STATE_STOPPED, 1);
        return minutes;
    }

    @Test
    void aSessionThatRunsOutOfCreditIsStoppedWithACheckpointAndChargedTheWholeWalletExactly() throws Exception {
        // 60 paise: exactly the 30-minute hold at 2 paise a minute, and nothing more.
        var running = running(60, 10_000);
        var renterId = running.booked().renter().id();

        boolean stopped = false;
        int index = 0;
        while (!stopped && index < 31) {
            runMinute(running, index);
            assertThat(balance(renterId)).isNotNegative();
            index++;
            stopped = stoppedForCredit(running);
        }
        assertThat(stopped).as("stopped before the credit ran out").isTrue();
        var finishing = bookings.session(running.booked().renter(), running.id());
        assertThat(finishing.get("state").asString()).isEqualTo("FINISHING");
        assertThat(finishing.get("events").findValuesAsString("reason")).last().isEqualTo("credit_exhausted");

        int minutes = runOutTheLease(running, index);

        var completed = bookings.awaitState(running.booked().renter(), running.id(), "COMPLETED");
        assertThat(completed.get("events").findValuesAsString("reason")).last().isEqualTo("stopped");
        bookings.awaitHoldReleased(running.id());
        assertThat(minutes).as("the last lease bought exactly the minutes left").isEqualTo(30);
        assertThat(balance(renterId))
                .as("30 minutes at 2 paise: every paisa, and not one more")
                .isZero();
        assertThat(bookings.session(running.booked().renter(), running.id())
                        .get("chargedPaise")
                        .asLong())
                .isEqualTo(60);

        // The renter is told, in the app and by email: it ran low, then it stopped, with what it cost (ADR 0026).
        assertThat(bookings.awaitNotification(renterId, "low-credit").get("title"))
                .isEqualTo("Your credit is running low");
        var told = bookings.awaitNotification(renterId, "stopped-for-credit");
        assertThat(told.get("title")).isEqualTo("Stopped: your credit ran out");
        assertThat(told.get("body").toString()).contains("It cost ₹0.60.");
    }

    @Test
    void aSessionThatReachesItsSpendingCapIsStoppedAndChargedNoMoreThanTheCap() throws Exception {
        var running = running(50_000, 6);
        var renterId = running.booked().renter().id();

        boolean stopped = false;
        int index = 0;
        while (!stopped && index < 4) {
            runMinute(running, index);
            index++;
            stopped = stoppedForCredit(running);
        }
        assertThat(stopped).isTrue();
        assertThat(bookings.session(running.booked().renter(), running.id())
                        .get("events")
                        .findValuesAsString("reason"))
                .last()
                .isEqualTo("max_spend_reached");
        int minutes = runOutTheLease(running, index);

        bookings.awaitState(running.booked().renter(), running.id(), "COMPLETED");
        bookings.awaitHoldReleased(running.id());
        var charged = 50_000 - balance(renterId);
        assertThat(charged).as("exactly the minutes it ran").isEqualTo(2L * minutes);
        assertThat(charged).as("and never past the cap").isLessThanOrEqualTo(6);
        assertThat(bookings.awaitNotification(renterId, "stopped-for-credit").get("title"))
                .isEqualTo("Stopped: it reached your spending cap");
    }

    @Test
    void aRenewalBetweenTwoCountsOfWhatsUsedNeverLetsTheLeaseOutrunTheHold() throws Exception {
        // A 6 paise cap at 2 paise a minute holds minutes 0, 1 and 2: no lease may let minute 3 begin.
        var running = running(50_000, 6);
        var runningAt = jdbc.queryForObject(
                        "select running_at from sessions.attempts where session_id = ? and attempt = 1",
                        java.sql.Timestamp.class,
                        running.id())
                .toInstant();
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (jdbc.queryForObject(
                                "select committed_paise from billing.reservations where session_id = ?",
                                Long.class,
                                running.id())
                        == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        // Metering counted minute 0. Its next turn waits, as a slow one or another instance's would, while the
        // clock moves into minute 1 and a renewal runs.
        try (var _ = meteringTurnHeld(running.id())) {
            clock.advance(Duration.ofSeconds(60));
            renew(running);

            var coveredUntil = jdbc.queryForObject(
                            "select max(covered_until) from sessions.lease_coverage where session_id = ?",
                            java.sql.Timestamp.class,
                            running.id())
                    .toInstant();
            assertThat(coveredUntil)
                    .as("the lease ends before minute 3, which the cap can't pay for")
                    .isBeforeOrEqualTo(runningAt.plus(Duration.ofMinutes(3)));
        }
    }

    /** Holds the session's metering row lock until closed, so every metering turn skips it meanwhile. */
    private AutoCloseable meteringTurnHeld(UUID sessionId) throws InterruptedException {
        var held = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var holder = Thread.ofPlatform()
                .start(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.queryForList(
                            "select 1 from metering.metered_sessions where session_id = ? for update", sessionId);
                    held.countDown();
                    try {
                        done.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(held.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        return () -> {
            done.countDown();
            holder.join();
        };
    }

    @Test
    void aStopTellsTheAgentToCheckpointFirstWithAGracePeriod() throws Exception {
        var running = running(50_000, 4);

        runMinute(running, 0);
        var stop = running.host().stream()
                .await(OpenStreamResponse::hasStopSession, WAIT)
                .getStopSession();

        assertThat(stop.getCheckpointFirst()).isTrue();
        assertThat(stop.getGracePeriod().getSeconds()).isEqualTo(60);
        assertThat(stop.getReason())
                .as("the 4 paise cap, not the wallet, is what ran out")
                .isEqualTo(StopReason.STOP_REASON_MAX_SPEND_REACHED);
        assertThat(stop.getAttempt()).isEqualTo(1);
    }

    @Test
    void aLeaseIsNotRenewedWhenTheHoldCantCoverIt() throws Exception {
        var booked = bookings.bookAndAccept();
        booked.host().ack(booked.host().awaitStart(WAIT));
        booked.host().stream().await(OpenStreamResponse::hasLeaseRenew, WAIT);

        // Everything held is committed: nothing left for another lease.
        var held = reservations.held(booked.sessionId()).orElseThrow();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> reservations.markCommitted(booked.sessionId(), held, clock.instant()));
        Thread.sleep(700);
        booked.host().stream().clear();

        assertThat(booked.host().stream().receives(OpenStreamResponse::hasLeaseRenew, Duration.ofSeconds(2)))
                .isFalse();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> reservations.markCommitted(booked.sessionId(), Paise.ZERO, clock.instant()));
        assertThat(booked.host().stream().receives(OpenStreamResponse::hasLeaseRenew, WAIT))
                .isTrue();
    }
}
