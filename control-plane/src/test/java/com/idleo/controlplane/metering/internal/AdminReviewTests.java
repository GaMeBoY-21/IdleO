package com.idleo.controlplane.metering.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.SessionCharges;
import com.idleo.controlplane.billing.internal.TestCredit;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.sessions.MeteredSessions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The review queue (ADR 0017, ADR 0027): each minute charged or waived once, with a reason, through the ledger's own
 * path, audited, and leaving every wallet equal to the ledger.
 */
@ControlPlaneTest
class AdminReviewTests {

    private static final AuditTrail.Actor OPERATOR = AuditTrail.Actor.operator("tester");

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
    AuditTrail audit;

    @Autowired
    MutableClock clock;

    @Autowired
    TransactionTemplate transactions;

    private final JsonMapper json = JsonMapper.builder().build();
    private final FakeMeteredSessions sessions = new FakeMeteredSessions();
    private final Instant startedAt =
            Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofMinutes(40));
    private ReviewQueue queue;

    @BeforeEach
    void setUp() {
        queue = new ReviewQueue(
                new Settlements(sessions, charges, reservations, jdbc), jdbc, transactionManager, audit, clock);
    }

    private record Fixture(UUID sessionId, UUID renterId, UUID hostId) {}

    /**
     * A closed session at {@code price} a minute, capped at {@code maxSpend}, whose renter has {@code credit}, with
     * minutes 0 to 2 in the review queue: one only the agent saw, one only the lease, one past the limit.
     */
    private Fixture closedSession(long credit, int price, long maxSpend) throws Exception {
        var renterId = TestUsers.signUp(mvc).id();
        var sessionId = UUID.randomUUID();
        var hostId = UUID.randomUUID();
        if (credit > 0) {
            TestCredit.grant(context, renterId, credit);
        }
        sessions.put(
                new MeteredSessions.Session(sessionId, renterId, maxSpend, startedAt, 1, hostId, false),
                new MeteredSessions.Attempt(1, hostId, price, 0, startedAt, startedAt, startedAt.plusSeconds(200)));
        jdbcTemplate.update(
                "insert into metering.metered_sessions (session_id, renter_id, started_at, max_spend_paise,"
                        + " next_window_at, ended_at, closed_at, created_at) values (?, ?, ?, ?, ?, ?, ?, now())",
                sessionId,
                renterId,
                Timestamp.from(startedAt),
                maxSpend,
                Timestamp.from(startedAt.plus(Duration.ofMinutes(15))),
                Timestamp.from(startedAt.plusSeconds(200)),
                Timestamp.from(startedAt.plusSeconds(500)));
        var reasons = new String[] {"agent_only", "lease_only", "over_limit"};
        for (int index = 0; index < 3; index++) {
            jdbcTemplate.update(
                    "insert into metering.review_minutes (session_id, minute_index, minute_at, attempt, reason,"
                            + " created_at) values (?, ?, ?, 1, ?, ?)",
                    sessionId,
                    index,
                    Timestamp.from(startedAt.plus(Duration.ofMinutes(index))),
                    reasons[index],
                    Timestamp.from(startedAt.plusSeconds(500)));
        }
        return new Fixture(sessionId, renterId, hostId);
    }

    private long balance(UUID renterId) {
        return jdbcTemplate.queryForObject(
                "select balance_paise from billing.wallets where user_id = ?", Long.class, renterId);
    }

    private int audited(String action, Fixture fixture, int minute) {
        return jdbcTemplate.queryForObject(
                "select count(*) from identity.audit_log where action = ? and target_id = ?",
                Integer.class,
                action,
                fixture.sessionId() + ":" + minute);
    }

    private static MockHttpServletRequestBuilder reason(MockHttpServletRequestBuilder request, String reason) {
        return request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}");
    }

    /** The wallet check, run from the console now, finds nothing wrong with this renter's wallet. */
    private void reconcilesToZero(TestAdmins.TestAdmin admin, UUID renterId) throws Exception {
        var run = json.readTree(mvc.perform(admin.as(reason(post("/admin/v1/reconciliation"), "after a review")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(run.get("ledgerBalanced").asBoolean()).isTrue();
        assertThat(run.get("mismatches").findValuesAsString("userId")).doesNotContain(renterId.toString());
    }

    @Test
    void chargingAMinuteSettlesItOnceAtItsAttemptsPriceAndReconciles() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbcTemplate, clock);
        var fixture = closedSession(10_000, 3, 1_000);

        var outcome =
                queue.charge(fixture.sessionId(), 0, "the agent's report is right; a lease row was lost", OPERATOR);
        assertThat(outcome).isInstanceOf(ReviewQueue.Outcome.Decided.class);
        var txnId = ((ReviewQueue.Outcome.Decided) outcome).settlementTxn();
        assertThat(txnId).isEqualTo(Settlements.txnId(fixture.sessionId(), 1));
        assertThat(balance(fixture.renterId())).isEqualTo(10_000 - 3);
        assertThat(jdbcTemplate.queryForMap(
                        "select minutes, amount_paise, window_end from metering.settlements where txn_id = ?", txnId))
                .containsEntry("minutes", 1)
                .containsEntry("amount_paise", 3L);
        assertThat(jdbcTemplate.queryForObject(
                        "select minutes from metering.settlement_parts where txn_id = ? and host_id = ?",
                        Integer.class,
                        txnId,
                        fixture.hostId()))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "select settlement_txn from metering.review_minutes where session_id = ? and minute_index = 0",
                        UUID.class,
                        fixture.sessionId()))
                .isEqualTo(txnId);

        // Once: again changes nothing, and it can't be waived now.
        assertThat(queue.charge(fixture.sessionId(), 0, "again", OPERATOR))
                .isEqualTo(new ReviewQueue.Outcome.AlreadyDecided("charged"));
        assertThat(queue.waive(fixture.sessionId(), 0, "changed my mind", OPERATOR))
                .isInstanceOf(ReviewQueue.Outcome.Refused.class);
        assertThat(balance(fixture.renterId())).isEqualTo(10_000 - 3);
        assertThat(audited("review_minute.charge", fixture, 0)).isOne();

        // A second charged minute is the next settlement.
        queue.charge(fixture.sessionId(), 1, "the lease shows it ran", OPERATOR);
        assertThat(jdbcTemplate.queryForObject(
                        "select settlements from metering.metered_sessions where session_id = ?",
                        Integer.class,
                        fixture.sessionId()))
                .isEqualTo(2);
        reconcilesToZero(admin, fixture.renterId());
    }

    @Test
    void waivingAMinuteMovesNoMoneyAndReconciles() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbcTemplate, clock);
        var fixture = closedSession(10_000, 3, 1_000);
        assertThat(queue.waive(fixture.sessionId(), 2, "over the renter's cap; not theirs to pay", OPERATOR))
                .isEqualTo(new ReviewQueue.Outcome.Decided("waived", null));
        assertThat(balance(fixture.renterId())).isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from metering.settlements where session_id = ?",
                        Integer.class,
                        fixture.sessionId()))
                .isZero();
        assertThat(audited("review_minute.waive", fixture, 2)).isOne();
        assertThat(queue.charge(fixture.sessionId(), 2, "no, charge it", OPERATOR))
                .isInstanceOf(ReviewQueue.Outcome.Refused.class);
        reconcilesToZero(admin, fixture.renterId());
    }

    @Test
    void aMinuteIsNeverChargedPastTheCapOrTheCredit() throws Exception {
        var capped = closedSession(10_000, 3, 2);
        assertThat(queue.charge(capped.sessionId(), 0, "worked", OPERATOR))
                .isInstanceOf(ReviewQueue.Outcome.Refused.class);
        var broke = closedSession(0, 3, 1_000);
        assertThat(queue.charge(broke.sessionId(), 0, "worked", OPERATOR))
                .isInstanceOf(ReviewQueue.Outcome.Refused.class);
        assertThat(audited("review_minute.charge", capped, 0)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from metering.review_minutes where session_id in (?, ?)"
                                + " and resolution is not null",
                        Integer.class,
                        capped.sessionId(),
                        broke.sessionId()))
                .isZero();
    }

    @Test
    void aChargeItsPostingAndItsAuditRowRollBackTogether() throws Exception {
        var fixture = closedSession(10_000, 3, 1_000);
        transactions.executeWithoutResult(status -> {
            queue.charge(fixture.sessionId(), 0, "rolled back", OPERATOR);
            status.setRollbackOnly();
        });
        assertThat(balance(fixture.renterId())).isEqualTo(10_000);
        assertThat(audited("review_minute.charge", fixture, 0)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "select resolution from metering.review_minutes where session_id = ? and minute_index = 0",
                        String.class,
                        fixture.sessionId()))
                .isNull();
    }

    @Test
    void theConsoleListsTheQueueOldestFirstAndDecidesEachMinute() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbcTemplate, clock);
        var fixture = closedSession(10_000, 3, 1_000);
        var open = json.readTree(mvc.perform(admin.as(get("/admin/v1/review-minutes")
                                .param("session", fixture.sessionId().toString())))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("minutes");
        assertThat(open.findValuesAsString("reason")).containsExactly("agent_only", "lease_only", "over_limit");

        mvc.perform(admin.as(reason(post("/admin/v1/review-minutes/" + fixture.sessionId() + "/1/waive"), "abc")))
                .andExpect(status().isBadRequest());
        mvc.perform(admin.as(reason(
                        post("/admin/v1/review-minutes/" + fixture.sessionId() + "/1/waive"), "the host was offline")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("waived"));
        // The real sessions module has never heard of this session, so there's no attempt to price it by.
        mvc.perform(admin.as(reason(
                        post("/admin/v1/review-minutes/" + fixture.sessionId() + "/0/charge"), "the agent was right")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/action-refused"));
        mvc.perform(admin.as(reason(post("/admin/v1/review-minutes/" + UUID.randomUUID() + "/0/charge"), "no such")))
                .andExpect(status().isNotFound());

        var decided = json.readTree(mvc.perform(admin.as(get("/admin/v1/review-minutes")
                                .param("state", "decided")
                                .param("session", fixture.sessionId().toString())))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("minutes");
        assertThat(decided).hasSize(1);
        assertThat(decided.get(0).get("resolvedBy").asString()).startsWith("admin ");
        assertThat(decided.get(0).get("resolutionReason").asString()).isEqualTo("the host was offline");
        JsonNode page = json.readTree(mvc.perform(admin.as(get("/admin/v1/review-minutes")
                        .param("session", fixture.sessionId().toString())
                        .param("limit", "1")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var next = json.readTree(mvc.perform(admin.as(get("/admin/v1/review-minutes")
                        .param("session", fixture.sessionId().toString())
                        .param("limit", "1")
                        .param("cursor", page.get("nextCursor").asString())))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(page.get("minutes").get(0).get("minuteIndex").asInt()).isZero();
        assertThat(next.get("minutes").get(0).get("minuteIndex").asInt()).isEqualTo(2);
    }
}
