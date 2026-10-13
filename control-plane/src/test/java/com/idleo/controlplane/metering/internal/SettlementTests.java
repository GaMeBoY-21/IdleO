package com.idleo.controlplane.metering.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.SessionCharges;
import com.idleo.controlplane.billing.SessionRefunds;
import com.idleo.controlplane.billing.internal.TestCredit;
import com.idleo.controlplane.sessions.MeteredSessions;
import com.idleo.controlplane.sessions.SessionControl;
import com.idleo.controlplane.sessions.SessionMilestones;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Settling minutes (ADR 0017): exactly once, in integer paise, never past the cap or the credit. */
@ControlPlaneTest
class SettlementTests {

    private static final Duration MINUTE = Duration.ofMinutes(1);

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

    @Autowired
    SessionRefunds refunds;

    private final FakeMeteredSessions sessions = new FakeMeteredSessions();
    private Settlements settlements;
    private final Instant startedAt =
            Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofMinutes(40));

    @BeforeEach
    void setUp() {
        settlements = new Settlements(sessions, charges, reservations, jdbc);
    }

    private record Fixture(UUID sessionId, UUID renterId, UUID hostId) {}

    /** A renter with {@code credit}, a session at {@code price} capped at {@code maxSpend}, and its 30-minute hold. */
    private Fixture session(long credit, int price, long maxSpend) {
        UUID renterId;
        try {
            renterId = TestUsers.signUp(mvc).id();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var sessionId = UUID.randomUUID();
        var hostId = UUID.randomUUID();
        if (credit > 0) {
            TestCredit.grant(context, renterId, credit);
        }
        sessions.put(
                new MeteredSessions.Session(sessionId, renterId, maxSpend, startedAt, 1, hostId, true),
                new MeteredSessions.Attempt(1, hostId, price, 0, startedAt, startedAt, null));
        jdbcTemplate.update(
                "insert into metering.metered_sessions"
                        + " (session_id, renter_id, started_at, max_spend_paise, next_window_at, created_at)"
                        + " values (?, ?, ?, ?, ?, now())",
                sessionId,
                renterId,
                Timestamp.from(startedAt),
                maxSpend,
                Timestamp.from(startedAt.plus(Duration.ofMinutes(15))));
        var hold = CreditReservations.holdFor(price, Paise.of(maxSpend));
        tx(() -> reservations.reserve(renterId, sessionId, hold));
        return new Fixture(sessionId, renterId, hostId);
    }

    private void minutes(UUID sessionId, int from, int count, boolean agent, boolean lease) {
        for (int index = from; index < from + count; index++) {
            jdbcTemplate.update(
                    "insert into metering.usage_minutes (session_id, minute_index, minute_at, agent_reported,"
                            + " agent_attempt, gpu_seconds, lease_alive, lease_checked, lease_attempt)"
                            + " values (?, ?, ?, ?, ?, ?, ?, true, 1)",
                    sessionId,
                    index,
                    Timestamp.from(startedAt.plus(MINUTE.multipliedBy(index))),
                    agent,
                    agent ? 1 : null,
                    agent ? 60 : null,
                    lease);
        }
    }

    private Settlements.Metered metered(Fixture fixture) {
        int done = jdbcTemplate.queryForObject(
                "select settlements from metering.metered_sessions where session_id = ?",
                Integer.class,
                fixture.sessionId());
        var maxSpend = sessions.session(fixture.sessionId()).orElseThrow().maxSpendPaise();
        return new Settlements.Metered(fixture.sessionId(), fixture.renterId(), maxSpend, done);
    }

    private Paise settle(Fixture fixture, Instant windowEnd) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> settlements.settle(metered(fixture), windowEnd, Instant.now()));
    }

    private void tx(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private static final SessionMilestones NO_MILESTONES = new SessionMilestones() {
        @Override
        public void settled(UUID sessionId) {}

        @Override
        public void lowOnCredit(UUID sessionId) {}
    };

    private MeteringJobs jobs() {
        return new MeteringJobs(
                sessions,
                new Witnesses(sessions, jdbc),
                settlements,
                new CreditGuard(
                        sessions,
                        context.getBean(SessionControl.class),
                        reservations,
                        settlements,
                        new Costs(sessions, settlements, refunds, jdbc),
                        NO_MILESTONES,
                        jdbc),
                new Waivers(sessions, charges, jdbc),
                reservations,
                sessionId -> {},
                NO_MILESTONES,
                jdbc,
                transactionManager,
                java.time.Clock.systemUTC(),
                Duration.ofMinutes(15),
                Duration.ofMinutes(5));
    }

    private Map<String, Object> wallet(UUID renterId) {
        return jdbcTemplate.queryForMap(
                "select balance_paise, reserved_paise from billing.wallets where user_id = ?", renterId);
    }

    private List<String> ledger(UUID txnId) {
        return jdbcTemplate.queryForList(
                "select account || ':' || direction || ':' || amount_paise from billing.ledger_entries"
                        + " where txn_id = ? order by account",
                String.class,
                txnId);
    }

    @Test
    void oneSettlementChargesTheWindowsMinutesSplitsTheMoneyAndMovesTheHold() {
        var fixture = session(10_000, 7, 100_000);
        minutes(fixture.sessionId(), 0, 20, true, true);

        var charged = settle(fixture, startedAt.plus(Duration.ofMinutes(15)));

        assertThat(charged).isEqualTo(Paise.of(15 * 7));
        var txnId = Settlements.txnId(fixture.sessionId(), 1);
        // 105 paise: the host gets 84 (80%, rounded down), the platform 21.
        assertThat(ledger(txnId))
                .containsExactly("host_payable:credit:84", "platform_revenue:credit:21", "renter_wallet:debit:105");
        assertThat(wallet(fixture.renterId()))
                .containsEntry("balance_paise", 10_000L - 105)
                .as("topped back up to 30 minutes")
                .containsEntry("reserved_paise", 210L);
        assertThat(jdbcTemplate.queryForList(
                        "select reason || ':' || before_paise || '->' || after_paise from billing.reservation_changes"
                                + " where session_id = ? order by created_at, reason desc",
                        String.class,
                        fixture.sessionId()))
                .containsExactly("reserved:0->210", "settled:210->105", "topped_up:105->210");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from metering.usage_minutes where session_id = ? and billed and settlement_txn = ?",
                        Integer.class,
                        fixture.sessionId(),
                        txnId))
                .isEqualTo(15);
    }

    @Test
    void aWindowAlreadySettledChargesNothingAndACrashMidSettlementLeavesNoTrace() {
        var fixture = session(10_000, 2, 100_000);
        minutes(fixture.sessionId(), 0, 10, true, true);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    settlements.settle(metered(fixture), null, Instant.now());
                    throw new IllegalStateException("the instance died here");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledger(Settlements.txnId(fixture.sessionId(), 1))).isEmpty();

        assertThat(settle(fixture, null)).isEqualTo(Paise.of(20));
        assertThat(settle(fixture, null)).isEqualTo(Paise.ZERO);
        assertThat(ledger(Settlements.txnId(fixture.sessionId(), 1))).hasSize(3);
        assertThat(wallet(fixture.renterId())).containsEntry("balance_paise", 10_000L - 20);
    }

    @Test
    void twoSettlersAtOnceSettleOnce() throws Exception {
        var fixture = session(10_000, 2, 100_000);
        minutes(fixture.sessionId(), 0, 10, true, true);
        var jobs = jobs();
        var barrier = new CyclicBarrier(2);
        Runnable settler = () -> {
            try {
                barrier.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> jobs.meter(fixture.sessionId()));
        };

        CompletableFuture.allOf(CompletableFuture.runAsync(settler), CompletableFuture.runAsync(settler))
                .get();

        // 40 minutes in: windows 1 and 2 have ended; 10 minutes, all in window 1, settled once.
        assertThat(jdbcTemplate.queryForObject(
                        "select coalesce(sum(amount_paise), 0) from metering.settlements where session_id = ?",
                        Long.class,
                        fixture.sessionId()))
                .isEqualTo(20L);
        assertThat(wallet(fixture.renterId())).containsEntry("balance_paise", 10_000L - 20);
    }

    @Test
    void minutesWithOneWitnessAreNeverCharged() {
        var fixture = session(10_000, 2, 100_000);
        minutes(fixture.sessionId(), 0, 3, true, false);
        minutes(fixture.sessionId(), 3, 3, false, true);
        minutes(fixture.sessionId(), 6, 2, true, true);

        assertThat(settle(fixture, null)).isEqualTo(Paise.of(4));
    }

    @Test
    void theSpendingCapStopsTheClaimAndTheRestIsNotCharged() {
        var fixture = session(10_000, 2, 6);
        minutes(fixture.sessionId(), 0, 5, true, true);

        assertThat(settle(fixture, null)).isEqualTo(Paise.of(6));
        assertThat(settle(fixture, null)).isEqualTo(Paise.ZERO);
        assertThat(wallet(fixture.renterId()))
                .containsEntry("balance_paise", 10_000L - 6)
                .as("nothing left of the cap to hold")
                .containsEntry("reserved_paise", 0L);
    }

    @Test
    void neverMoreThanTheHoldPlusTheBalanceSoTheWalletNeverGoesNegative() {
        var fixture = session(50, 2, 100_000);
        minutes(fixture.sessionId(), 0, 40, true, true);

        assertThat(settle(fixture, null)).isEqualTo(Paise.of(50));
        assertThat(wallet(fixture.renterId()))
                .containsEntry("balance_paise", 0L)
                .containsEntry("reserved_paise", 0L);
    }

    @Test
    void theHostsShareIsRoundedDownAndThePlatformKeepsTheRest() {
        assertThat(SessionCharges.split(Paise.of(7), 20))
                .isEqualTo(new SessionCharges.Split(Paise.of(7), Paise.of(5), Paise.of(2)));
        assertThat(SessionCharges.split(Paise.of(1200), 20))
                .isEqualTo(new SessionCharges.Split(Paise.of(1200), Paise.of(960), Paise.of(240)));
        assertThat(SessionCharges.split(Paise.of(6), 20))
                .isEqualTo(new SessionCharges.Split(Paise.of(6), Paise.of(4), Paise.of(2)));
        assertThat(SessionCharges.split(Paise.of(1), 20))
                .isEqualTo(new SessionCharges.Split(Paise.of(1), Paise.ZERO, Paise.of(1)));
        assertThat(SessionCharges.split(Paise.of(7), 15))
                .isEqualTo(new SessionCharges.Split(Paise.of(7), Paise.of(5), Paise.of(2)));
        assertThat(SessionCharges.split(Paise.of(99), 25))
                .isEqualTo(new SessionCharges.Split(Paise.of(99), Paise.of(74), Paise.of(25)));
        assertThat(SessionCharges.split(Paise.of(10), 0))
                .isEqualTo(new SessionCharges.Split(Paise.of(10), Paise.of(10), Paise.ZERO));
    }

    @Test
    void closingSettlesWhatsLeftReviewsTheRestAndReleasesTheHold() {
        var fixture = session(10_000, 2, 100_000);
        minutes(fixture.sessionId(), 0, 3, true, true);
        minutes(fixture.sessionId(), 3, 1, true, false);
        jdbcTemplate.update(
                "update metering.metered_sessions set ended_at = ?, next_window_at = ? where session_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(6))),
                Timestamp.from(Instant.now().plus(Duration.ofHours(1))),
                fixture.sessionId());
        var jobs = jobs();

        tx(() -> jobs.meter(fixture.sessionId()));

        assertThat(wallet(fixture.renterId()))
                .containsEntry("balance_paise", 10_000L - 6)
                .containsEntry("reserved_paise", 0L);
        assertThat(jdbcTemplate.queryForList(
                        "select minute_index || ':' || reason from metering.review_minutes where session_id = ?",
                        String.class,
                        fixture.sessionId()))
                .containsExactly("3:agent_only");
        assertThat(jdbcTemplate.queryForObject(
                        "select closed_at is not null from metering.metered_sessions where session_id = ?",
                        Boolean.class,
                        fixture.sessionId()))
                .isTrue();
    }
}
