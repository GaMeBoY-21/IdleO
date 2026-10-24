package com.idleo.controlplane.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.SessionCharges;
import com.idleo.controlplane.billing.internal.TestCredit;
import com.idleo.controlplane.sessions.SessionEnded;
import com.idleo.controlplane.sessions.SessionNeverRan;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports and refunds (ADR 0028): a renter reports their own ended session within 7 days, one open at a time; an
 * admin resolves it once, with a refund from the commission first and then the host, never more than was charged,
 * audited, with every wallet still equal to the ledger; while it's open the host's earnings from it are held; and
 * the host never learns who reported it or what they wrote.
 */
@ControlPlaneTest
class DisputeTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 30 minutes at 10 paise: the host keeps 240, the platform 60. */
    private static final long CHARGED = 300;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    CreditReservations reservations;

    @Autowired
    SessionCharges charges;

    private EndedSessions ended;
    private TestUsers.TestUser renter;
    private TestUsers.TestUser owner;
    private UUID hostId;
    private TestAdmins.TestAdmin admin;

    @BeforeEach
    void setUp() throws Exception {
        ended = new EndedSessions(jdbc, clock);
        renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        owner = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        hostId = UUID.fromString(JSON.readTree(mvc.perform(post("/v1/hosts")
                                .header("Authorization", owner.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Reported laptop\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString());
        TestCredit.grant(context, renter.id(), 10_000);
        admin = TestAdmins.signIn(mvc, jdbcTemplate, clock);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** A session of the renter's on the host, charged {@link #CHARGED} and ended {@code endedAgo} ago. */
    private UUID chargedSession(Duration endedAgo) {
        var sessionId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> reservations.reserve(renter.id(), sessionId, Paise.of(CHARGED)));
        transactions.executeWithoutResult(status -> charges.settle(new SessionCharges.Charge(
                sessionId,
                renter.id(),
                UUID.randomUUID(),
                List.of(new SessionCharges.Part(hostId, Paise.of(CHARGED))),
                Paise.ZERO)));
        transactions.executeWithoutResult(status -> reservations.release(sessionId));
        var endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS).minus(endedAgo);
        ended.on(new SessionEnded(
                sessionId, renter.id(), hostId, "COMPLETED", endedAt.minus(Duration.ofMinutes(30)), endedAt));
        return sessionId;
    }

    private UUID chargedSession() {
        return chargedSession(Duration.ofMinutes(1));
    }

    private ResultActions report(TestUsers.TestUser user, String key, String body) throws Exception {
        return mvc.perform(post("/v1/disputes")
                .header("Authorization", user.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private UUID reported(UUID sessionId, String reason, String details) throws Exception {
        var body = report(
                        renter,
                        UUID.randomUUID().toString(),
                        "{\"sessionId\":\"" + sessionId + "\",\"reason\":\"" + reason + "\",\"details\":\"" + details
                                + "\"}")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JSON.readTree(body).get("id").asString());
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private ResultActions refund(UUID disputeId, long paise, String reason) throws Exception {
        return mvc.perform(admin.as(body(
                post("/admin/v1/disputes/" + disputeId + "/refund"),
                "{\"amountPaise\":" + paise + ",\"reason\":\"" + reason + "\"}")));
    }

    private ResultActions noRefund(UUID disputeId, String reason) throws Exception {
        return mvc.perform(admin.as(
                body(post("/admin/v1/disputes/" + disputeId + "/no-refund"), "{\"reason\":\"" + reason + "\"}")));
    }

    private long balance() {
        return jdbcTemplate.queryForObject(
                "select balance_paise from billing.wallets where user_id = ?", Long.class, renter.id());
    }

    private int audited(String action, UUID disputeId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from identity.audit_log where action = ? and target_id = ?",
                Integer.class,
                action,
                disputeId.toString());
    }

    private JsonNode earnings() throws Exception {
        return JSON.readTree(mvc.perform(get("/v1/earnings").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    /** The wallet check, run from the console now: the ledger balances and the renter's wallet equals it. */
    private void reconcilesToZero() throws Exception {
        var run = JSON.readTree(
                mvc.perform(admin.as(body(post("/admin/v1/reconciliation"), "{\"reason\":\"after a refund\"}")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(run.get("ledgerBalanced").asBoolean()).isTrue();
        assertThat(run.get("mismatches").findValuesAsString("userId"))
                .doesNotContain(renter.id().toString());
    }

    @Test
    void aRenterReportsTheirEndedSessionOnceEverAndReadsItsState() throws Exception {
        var sessionId = chargedSession();
        var key = UUID.randomUUID().toString();
        var request = "{\"sessionId\":\"" + sessionId + "\",\"reason\":\"failed\",\"details\":\"  It stopped. \"}";
        var first = report(renter, key, request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("open"))
                .andExpect(jsonPath("$.reason").value("failed"))
                .andExpect(jsonPath("$.details").value("It stopped."))
                .andReturn()
                .getResponse();
        var id = JSON.readTree(first.getContentAsString()).get("id").asString();
        assertThat(first.getHeader("Location")).isEqualTo("/v1/disputes/" + id);
        // The same request with the same key is the same report.
        report(renter, key, request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id));
        // Another one while it's open is refused.
        report(renter, UUID.randomUUID().toString(), request.replace("failed", "slow"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/report-already-open"));

        mvc.perform(get("/v1/disputes?session=" + sessionId).header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disputes.length()").value(1))
                .andExpect(jsonPath("$.disputes[0].state").value("open"));

        // Resolved without a refund, with words the renter reads; and that was the session's one report.
        noRefund(UUID.fromString(id), "The job's own code failed; the GPU was fine.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispute.state").value("not_refunded"));
        mvc.perform(get("/v1/disputes/" + id).header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("not_refunded"))
                .andExpect(jsonPath("$.resolutionReason").value("The job's own code failed; the GPU was fine."))
                .andExpect(jsonPath("$.refundPaise").doesNotExist())
                .andExpect(jsonPath("$.resolvedBy").doesNotExist());
        // Another report after the resolution is refused, so it can never place another payout hold.
        report(renter, UUID.randomUUID().toString(), request.replace("failed", "charged_wrong"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/report-already-made"));
        mvc.perform(get("/v1/disputes?session=" + sessionId).header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.disputes.length()").value(1));
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from billing.payout_holds where session_id = ? and released_at is null",
                        Integer.class,
                        sessionId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from billing.payout_holds where session_id = ?", Integer.class, sessionId))
                .as("the one report's hold, released")
                .isOne();
    }

    @Test
    void aSessionCanBeReportedOnlyByItsRenterWithinSevenDaysOfEnding() throws Exception {
        var lastWeek = chargedSession(Duration.ofDays(7));
        report(renter, UUID.randomUUID().toString(), "{\"sessionId\":\"" + lastWeek + "\",\"reason\":\"slow\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/report-window-closed"));

        var recent = chargedSession(Duration.ofDays(6));
        var someoneElse = TestUsers.signUp(mvc);
        report(someoneElse, UUID.randomUUID().toString(), "{\"sessionId\":\"" + recent + "\",\"reason\":\"slow\"}")
                .andExpect(status().isNotFound());
        report(
                        renter,
                        UUID.randomUUID().toString(),
                        "{\"sessionId\":\"" + UUID.randomUUID() + "\",\"reason\":\"slow\"}")
                .andExpect(status().isNotFound());
        var id = reported(recent, "slow", "Half the speed I expected.");
        mvc.perform(get("/v1/disputes/" + id).header("Authorization", someoneElse.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/disputes").header("Authorization", someoneElse.bearer()))
                .andExpect(jsonPath("$.disputes").isEmpty());

        // A reason from the list, and at most 2,000 characters of details.
        report(renter, UUID.randomUUID().toString(), "{\"sessionId\":\"" + recent + "\",\"reason\":\"bored\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        report(
                        renter,
                        UUID.randomUUID().toString(),
                        "{\"sessionId\":\"" + recent + "\",\"reason\":\"other\",\"details\":\"" + "x".repeat(2_001)
                                + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void aRefundComesFromTheCommissionFirstThenTheHostAndReconcilesToZero() throws Exception {
        var sessionId = chargedSession();
        var id = reported(sessionId, "wrong_output", "The adapter was empty.");
        mvc.perform(admin.as(get("/admin/v1/disputes/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.paidPaise").value(CHARGED))
                .andExpect(jsonPath("$.session.commissionPaise").value(60))
                .andExpect(jsonPath("$.session.hosts[0].hostId").value(hostId.toString()))
                .andExpect(jsonPath("$.session.hosts[0].paise").value(240))
                .andExpect(jsonPath("$.dispute.details").value("The adapter was empty."));
        long before = balance();

        refund(id, 100, "The output was empty; part of it back.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispute.state").value("refunded"))
                .andExpect(jsonPath("$.dispute.refundPaise").value(100))
                .andExpect(jsonPath("$.dispute.commissionRefundPaise").value(60))
                .andExpect(jsonPath("$.dispute.hostRefundPaise").value(40))
                .andExpect(jsonPath("$.session.paidPaise").value(CHARGED - 100))
                .andExpect(jsonPath("$.session.commissionPaise").value(0))
                .andExpect(jsonPath("$.session.hosts[0].paise").value(200));
        assertThat(balance()).isEqualTo(before + 100);

        // One balanced transaction: the renter credited, the platform's revenue and the host's payable debited.
        var txn = Disputes.refundTxnId(id);
        assertThat(jdbcTemplate.queryForList(
                        "select account, direction, amount_paise from billing.ledger_entries where txn_id = ?"
                                + " order by account",
                        txn))
                .extracting(row -> row.get("account") + " " + row.get("direction") + " " + row.get("amount_paise"))
                .containsExactly("host_payable debit 40", "platform_revenue debit 60", "renter_wallet credit 100");

        // The renter's history and the host's earnings show it.
        mvc.perform(get("/v1/wallet/transactions").header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.entries[0].kind").value("dispute_refund"))
                .andExpect(jsonPath("$.entries[0].amountPaise").value(100))
                .andExpect(jsonPath("$.entries[0].direction").value("credit"));
        var history = earnings().get("history").get("entries");
        assertThat(history.get(0).get("kind").asString()).isEqualTo("refunded_to_renter");
        assertThat(history.get(0).get("label").asString()).isEqualTo("Refunded to renter");
        assertThat(history.get(0).get("amountPaise").asLong()).isEqualTo(-40);
        assertThat(earnings().get("totalPaise").asLong()).isEqualTo(200);

        assertThat(audited("dispute.refund", id)).isOne();
        reconcilesToZero();
    }

    @Test
    void theHostStoppedItIsAReasonAndAnAdminMayFlagTheHostAndReadTheLogAudited() throws Exception {
        var sessionId = chargedSession();
        var id = reported(sessionId, "host_stopped", "It stopped halfway, twice.");

        // Its log, read with why, audited once a read.
        mvc.perform(admin.as(get("/admin/v1/sessions/" + sessionId + "/log?reason=x")))
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        mvc.perform(admin.as(get("/admin/v1/sessions/" + sessionId + "/log?reason=judging report " + id)))
                .andExpect(status().isNotFound());

        mvc.perform(admin.as(body(
                        post("/admin/v1/disputes/" + id + "/refund"),
                        "{\"amountPaise\":120,\"reason\":\"The host stopped it twice.\",\"flagHost\":true}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispute.state").value("refunded"))
                .andExpect(jsonPath("$.hostFlags.length()").value(1))
                .andExpect(jsonPath("$.hostFlags[0].disputeId").value(id.toString()))
                .andExpect(jsonPath("$.hostFlags[0].reason").value("The host stopped it twice."));
        mvc.perform(admin.as(get("/admin/v1/disputes/host-flags?host=" + hostId)))
                .andExpect(jsonPath("$.flags.length()").value(1));
        assertThat(jdbcTemplate.queryForObject(
                        "select after ->> 'flaggedHost' from identity.audit_log where action = 'dispute.refund'"
                                + " and target_id = ?",
                        String.class,
                        id.toString()))
                .isEqualTo(hostId.toString());
        // The give-back is free credit that never expires (ADR 0039).
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from billing.credit_lots where user_id = ? and kind = 'free'"
                                + " and expires_at is null and amount_paise = 120",
                        Integer.class,
                        renter.id()))
                .isOne();
        // The host's owner sees nothing of it.
        mvc.perform(get("/admin/v1/disputes/host-flags?host=" + hostId).header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRefundIsNeverMoreThanWasChargedAndAReportIsResolvedOnce() throws Exception {
        var sessionId = chargedSession();
        var id = reported(sessionId, "charged_wrong", "I was charged twice.");

        refund(id, CHARGED + 1, "More than it cost.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/action-refused"));
        refund(id, 0, "Nothing at all.").andExpect(status().isBadRequest());
        refund(id, 10, "no").andExpect(status().isBadRequest());
        assertThat(audited("dispute.refund", id)).isZero();

        refund(id, CHARGED, "All of it back: our mistake.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispute.commissionRefundPaise").value(60))
                .andExpect(jsonPath("$.dispute.hostRefundPaise").value(240))
                .andExpect(jsonPath("$.session.paidPaise").value(0));
        long after = balance();
        // The same refund again changes nothing; another resolution is refused.
        refund(id, CHARGED, "All of it back: our mistake.").andExpect(status().isOk());
        noRefund(id, "Changed my mind about it.").andExpect(status().isConflict());
        refund(id, 5, "A different amount.").andExpect(status().isConflict());
        assertThat(balance()).isEqualTo(after);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(distinct txn_id) from billing.ledger_entries where ref_type = 'dispute_refund'"
                                + " and ref_id = ?",
                        Integer.class,
                        sessionId))
                .isOne();
        assertThat(audited("dispute.refund", id)).isOne();
        assertThat(audited("dispute.no_refund", id)).isZero();

        // A session is reported once: there is no later report to try another refund on.
        report(renter, UUID.randomUUID().toString(), "{\"sessionId\":\"" + sessionId + "\",\"reason\":\"other\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/report-already-made"));
        reconcilesToZero();
    }

    @Test
    void anOpenReportHoldsTheHostsEarningsFromItAndTheHostNeverLearnsWhoOrWhat() throws Exception {
        var sessionId = chargedSession();
        var other = chargedSession();
        assertThat(earnings().get("history").get("entries"))
                .allSatisfy(
                        entry -> assertThat(entry.get("payoutHeld").asBoolean()).isFalse());

        var id = reported(sessionId, "slow", "Secret details only admins read.");
        for (var entry : earnings().get("history").get("entries")) {
            assertThat(entry.get("payoutHeld").asBoolean())
                    .as("held only for the reported session")
                    .isEqualTo(entry.get("sessionId").asString().equals(sessionId.toString()));
        }
        assertThat(other).isNotEqualTo(sessionId);
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from billing.payout_holds where session_id = ? and released_at is null",
                        Integer.class,
                        sessionId))
                .isOne();

        var seen = mvc.perform(get("/v1/earnings").header("Authorization", owner.bearer()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                + mvc.perform(get("/v1/hosts/" + hostId).header("Authorization", owner.bearer()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString()
                + mvc.perform(get("/v1/hosts/" + hostId + "/earnings").header("Authorization", owner.bearer()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(seen).doesNotContain(renter.id().toString(), "Secret details", id.toString());

        noRefund(id, "It ran at the speed of its GPU.").andExpect(status().isOk());
        assertThat(earnings().get("history").get("entries"))
                .allSatisfy(
                        entry -> assertThat(entry.get("payoutHeld").asBoolean()).isFalse());
    }

    @Test
    void aSessionThatNeverRanCanBeReportedButHasNothingToRefund() throws Exception {
        var sessionId = UUID.randomUUID();
        ended.on(new SessionNeverRan(sessionId, renter.id(), "FAILED", clock.instant()));
        var id = reported(sessionId, "didnt_start", "It never started.");
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from billing.payout_holds where session_id = ?", Integer.class, sessionId))
                .as("no host earned anything from it")
                .isZero();
        mvc.perform(admin.as(get("/admin/v1/disputes/" + id)))
                .andExpect(jsonPath("$.session.paidPaise").value(0))
                .andExpect(jsonPath("$.session.hostId").doesNotExist());
        refund(id, 1, "Something for the trouble.").andExpect(status().isConflict());
        noRefund(id, "Nothing was charged for it.").andExpect(status().isOk());
    }

    @Test
    void theQueueListsOpenReportsFirstOldestFirstAndTheDetailHasTheRating() throws Exception {
        var older = reported(chargedSession(), "slow", "First.");
        var resolved = reported(chargedSession(), "slow", "Second.");
        var newer = reported(chargedSession(), "slow", "Third.");
        noRefund(resolved, "Nothing wrong with it.").andExpect(status().isOk());

        var page = JSON.readTree(mvc.perform(admin.as(get("/admin/v1/disputes?limit=200")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var ids = new java.util.ArrayList<String>();
        page.get("disputes").forEach(dispute -> ids.add(dispute.get("id").asString()));
        assertThat(ids).containsSubsequence(older.toString(), newer.toString(), resolved.toString());
        var open = JSON.readTree(mvc.perform(admin.as(get("/admin/v1/disputes?state=open&limit=1")))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(open.get("disputes")).hasSize(1);
        assertThat(open.get("disputes").get(0).get("state").asString()).isEqualTo("open");
        mvc.perform(admin.as(get("/admin/v1/disputes?state=open&cursor="
                        + open.get("nextCursor").asString())))
                .andExpect(jsonPath("$.disputes[*].state")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("open"))));

        // The detail carries the session's rating and its comment, which only admins read.
        var rated = chargedSession();
        mvc.perform(post("/v1/sessions/" + rated + "/rating")
                        .header("Authorization", renter.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":2,\"comment\":\"Slow and it failed.\"}"))
                .andExpect(status().isOk());
        var withRating = reported(rated, "failed", "See my rating.");
        mvc.perform(admin.as(get("/admin/v1/disputes/" + withRating)))
                .andExpect(jsonPath("$.rating.stars").value(2))
                .andExpect(jsonPath("$.rating.comment").value("Slow and it failed."));
        mvc.perform(admin.as(get("/admin/v1/ratings?session=" + rated)))
                .andExpect(jsonPath("$.ratings[0].comment").value("Slow and it failed."));

        // Anyone short of an admin session finds nothing there.
        mvc.perform(get("/admin/v1/disputes/" + withRating).header("Authorization", renter.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/v1/ratings").header("Authorization", renter.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(admin.as(post("/admin/v1/disputes/" + UUID.randomUUID() + "/no-refund"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"no such report\"}"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Location"));
    }
}
