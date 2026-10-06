package com.idleo.controlplane.metering.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.SessionCharges;
import com.idleo.controlplane.billing.internal.TestCredit;
import com.idleo.controlplane.sessions.MeteredSessions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Minutes lost with a host are given back (ADR 0017): never charged if unsettled, refunded once, in the original
 * proportions, if settled.
 */
@ControlPlaneTest
class WaiverTests {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final UUID HOST = UUID.randomUUID();

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    SessionCharges charges;

    @Autowired
    CreditReservations reservations;

    private final FakeMeteredSessions sessions = new FakeMeteredSessions();
    private Settlements settlements;
    private Waivers waivers;
    private final Instant startedAt =
            Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofMinutes(40));

    @BeforeEach
    void setUp() {
        settlements = new Settlements(sessions, charges, reservations, jdbc);
        waivers = new Waivers(sessions, charges, jdbc);
    }

    private record Fixture(UUID sessionId, UUID renterId) {}

    /** A renter with 10,000 paise and a session at 2 paise a minute, its attempt 1 running from minute 0. */
    private Fixture session() throws Exception {
        var renterId = TestUsers.signUp(mvc).id();
        var sessionId = UUID.randomUUID();
        TestCredit.grant(context, renterId, 10_000);
        sessions.put(
                new MeteredSessions.Session(sessionId, renterId, 100_000, startedAt, 1, HOST, true),
                new MeteredSessions.Attempt(1, HOST, 2, 0, startedAt, startedAt, null));
        jdbcTemplate.update(
                "insert into metering.metered_sessions"
                        + " (session_id, renter_id, started_at, max_spend_paise, next_window_at, created_at)"
                        + " values (?, ?, ?, ?, ?, now())",
                sessionId,
                renterId,
                Timestamp.from(startedAt),
                100_000,
                Timestamp.from(startedAt.plus(Duration.ofMinutes(15))));
        tx(() -> reservations.reserve(renterId, sessionId, Paise.of(60)));
        return new Fixture(sessionId, renterId);
    }

    /** Minutes {@code from} to {@code from + count - 1}, seen by both witnesses. */
    private void minutes(UUID sessionId, int from, int count) {
        for (int index = from; index < from + count; index++) {
            jdbcTemplate.update(
                    "insert into metering.usage_minutes (session_id, minute_index, minute_at, agent_reported,"
                            + " agent_attempt, gpu_seconds, lease_alive, lease_checked, lease_attempt)"
                            + " values (?, ?, ?, true, 1, 60, true, true, 1)",
                    sessionId,
                    index,
                    Timestamp.from(startedAt.plus(MINUTE.multipliedBy(index))));
        }
    }

    private Settlements.Metered metered(Fixture fixture) {
        int done = jdbcTemplate.queryForObject(
                "select settlements from metering.metered_sessions where session_id = ?",
                Integer.class,
                fixture.sessionId());
        return new Settlements.Metered(fixture.sessionId(), fixture.renterId(), 100_000, done);
    }

    private Paise settle(Fixture fixture, @Nullable Instant windowEnd) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> settlements.settle(metered(fixture), windowEnd, Instant.now()));
    }

    private void waive(Fixture fixture, @Nullable Instant checkpointAt) {
        tx(() -> waivers.waive(fixture.sessionId(), 1, checkpointAt, Instant.now()));
    }

    private void tx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private long balance(UUID renterId) {
        return jdbcTemplate.queryForObject(
                "select balance_paise from billing.wallets where user_id = ?", Long.class, renterId);
    }

    private List<String> ledger(UUID txnId) {
        return jdbcTemplate.queryForList(
                "select account || ':' || direction || ':' || amount_paise from billing.ledger_entries"
                        + " where txn_id = ? order by account",
                String.class,
                txnId);
    }

    private int waived(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from metering.usage_minutes where session_id = ? and waived",
                Integer.class,
                sessionId);
    }

    @Test
    void aRefundReversesAPartInItsOwnProportions() {
        var part = new SessionCharges.HostShare(HOST, Paise.of(20), Paise.of(16));

        assertThat(Waivers.refundPart(part, Paise.of(20)))
                .as("the whole part: exactly what each was credited")
                .isEqualTo(new SessionCharges.RefundPart(HOST, Paise.of(16), Paise.of(4)));
        assertThat(Waivers.refundPart(part, Paise.of(12)))
                .as("12 of 20: the host gives back 9.6, rounded down, the platform the rest")
                .isEqualTo(new SessionCharges.RefundPart(HOST, Paise.of(9), Paise.of(3)));
        assertThat(Waivers.refundPart(part, Paise.ZERO))
                .isEqualTo(new SessionCharges.RefundPart(HOST, Paise.ZERO, Paise.ZERO));
        // 7 paise at 20%: the host got 5, the platform 2; refunding 2 of it takes 1 from each.
        assertThat(Waivers.refundPart(new SessionCharges.HostShare(HOST, Paise.of(7), Paise.of(5)), Paise.of(2)))
                .isEqualTo(new SessionCharges.RefundPart(HOST, Paise.of(1), Paise.of(1)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Waivers.refundPart(part, Paise.of(21)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minutesAfterTheCheckpointThatWereNeverSettledAreNeverCharged() throws Exception {
        var fixture = session();
        minutes(fixture.sessionId(), 0, 10);

        // The checkpoint was written 4.5 minutes in: minute 4, half of which was lost, is given back too.
        waive(fixture, startedAt.plus(Duration.ofSeconds(270)));

        assertThat(waived(fixture.sessionId())).isEqualTo(6);
        assertThat(ledger(Waivers.txnId(fixture.sessionId(), 1)))
                .as("nothing was settled: nothing to refund")
                .isEmpty();
        assertThat(settle(fixture, null)).isEqualTo(Paise.of(4 * 2));
        assertThat(balance(fixture.renterId())).isEqualTo(10_000 - 8);
        assertThat(settlements.chargedNet(fixture.sessionId())).isEqualTo(Paise.of(8));
    }

    @Test
    void minutesAlreadySettledAreRefundedOnceInOneReversingTransaction() throws Exception {
        var fixture = session();
        minutes(fixture.sessionId(), 0, 10);
        assertThat(settle(fixture, null)).isEqualTo(Paise.of(20));

        waive(fixture, startedAt.plus(Duration.ofSeconds(270)));
        waive(fixture, startedAt.plus(Duration.ofSeconds(270)));

        // 6 of the 10 settled minutes: 12 of the 20 paise. The host was credited 16 of 20 and gives back 9.
        var refund = Waivers.txnId(fixture.sessionId(), 1);
        assertThat(ledger(refund))
                .containsExactly("host_payable:debit:9", "platform_revenue:debit:3", "renter_wallet:credit:12");
        assertThat(balance(fixture.renterId())).isEqualTo(10_000 - 20 + 12);
        assertThat(settlements.chargedNet(fixture.sessionId())).isEqualTo(Paise.of(8));
        assertThat(jdbcTemplate.queryForMap(
                        "select refunded_minutes, refunded_paise, host_refund_paise, platform_refund_paise"
                                + " from metering.waivers where session_id = ?",
                        fixture.sessionId()))
                .containsEntry("refunded_minutes", 6)
                .containsEntry("refunded_paise", 12L)
                .containsEntry("host_refund_paise", 9L)
                .containsEntry("platform_refund_paise", 3L);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from metering.usage_minutes where session_id = ? and refund_txn = ?",
                        Integer.class,
                        fixture.sessionId(),
                        refund))
                .isEqualTo(6);
        assertThat(settle(fixture, null)).isEqualTo(Paise.ZERO);
    }

    @Test
    void aWaiverAcrossSettledAndUnsettledMinutesRefundsOnlyWhatWasCharged() throws Exception {
        var fixture = session();
        minutes(fixture.sessionId(), 0, 10);
        assertThat(settle(fixture, startedAt.plus(Duration.ofMinutes(6)))).isEqualTo(Paise.of(12));

        waive(fixture, startedAt.plus(Duration.ofSeconds(210)));

        // Minutes 3 to 5 were settled (6 of the window's 12 paise; the host had 9 of them), 6 to 9 weren't.
        assertThat(ledger(Waivers.txnId(fixture.sessionId(), 1)))
                .containsExactly("host_payable:debit:4", "platform_revenue:debit:2", "renter_wallet:credit:6");
        assertThat(settle(fixture, null)).isEqualTo(Paise.ZERO);
        assertThat(settlements.chargedNet(fixture.sessionId())).isEqualTo(Paise.of(6));
        assertThat(balance(fixture.renterId())).isEqualTo(10_000 - 6);
    }

    @Test
    void anAttemptWithNoCheckpointGivesBackEveryMinute() throws Exception {
        var fixture = session();
        minutes(fixture.sessionId(), 0, 5);
        assertThat(settle(fixture, null)).isEqualTo(Paise.of(10));

        waive(fixture, null);

        assertThat(ledger(Waivers.txnId(fixture.sessionId(), 1)))
                .containsExactly("host_payable:debit:8", "platform_revenue:debit:2", "renter_wallet:credit:10");
        assertThat(balance(fixture.renterId())).isEqualTo(10_000);
        assertThat(settlements.chargedNet(fixture.sessionId())).isEqualTo(Paise.ZERO);
    }

    @Test
    void minutesThatArriveAfterTheWaiverAreNeverCharged() throws Exception {
        var fixture = session();
        minutes(fixture.sessionId(), 0, 3);
        waive(fixture, startedAt.plus(Duration.ofSeconds(120)));

        // The agent reconnects and resends minutes it had kept.
        minutes(fixture.sessionId(), 3, 4);

        assertThat(settle(fixture, null)).as("minutes 0 and 1 only").isEqualTo(Paise.of(4));
        tx(() -> waivers.waiveLateMinutes(fixture.sessionId()));
        assertThat(waived(fixture.sessionId())).isEqualTo(5);
        assertThat(ledger(Waivers.txnId(fixture.sessionId(), 1))).isEmpty();
    }
}
