package com.idleo.controlplane.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.billing.HostPayouts;
import com.idleo.controlplane.billing.PayoutHolds;
import com.idleo.controlplane.razorpay.FakeRazorpayX;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Paying hosts (ADR 0040), against the fake RazorpayX: earnings wait out the dispute window and any open report, a
 * payout moves the money into flight before RazorpayX is asked, and every outcome RazorpayX can give puts the money
 * where it belongs, once, however often and in whatever order its events come.
 */
@ControlPlaneTest
class PayoutsTests {

    static final FakeRazorpayX RAZORPAYX = TestRazorpayConfiguration.RAZORPAYX;
    static final Duration PAST_WINDOW = Duration.ofDays(7).plusMinutes(5);

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HostPayouts ledger;

    @Autowired
    PayoutService payouts;

    @Autowired
    PayoutRuns runs;

    @Autowired
    PayoutHolds holds;

    @Autowired
    PlatformTransactionManager transactionManager;

    Payees payees;

    @BeforeEach
    void setUp() {
        payees = new Payees(mvc, context);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private long balance(String account, UUID owner) {
        return Payees.balance(jdbc, account, owner);
    }

    private PayoutService.Payout paid(Payees.Owner owner) {
        var outcome = payouts.create(owner.id(), "weekly", null, Paise.of(100));
        assertThat(outcome.notPaid()).isNull();
        payouts.send(outcome.payout().id());
        return payouts.find(outcome.payout().id()).orElseThrow();
    }

    @Test
    void earningsArePendingForTheDisputeWindowThenAvailable() throws Exception {
        var owner = payees.owner();
        payees.earn(owner.hostId(), 1_000);

        var during = ledger.position(owner.id());
        assertThat(during.pendingPaise()).isEqualTo(800);
        assertThat(during.availablePaise()).isZero();
        assertThat(during.pending()).singleElement().satisfies(session -> {
            assertThat(session.availableAt())
                    .isBetween(
                            clock.instant().plus(Duration.ofDays(7)).minusSeconds(60),
                            clock.instant().plus(Duration.ofDays(7)));
        });

        clock.advance(PAST_WINDOW);
        var after = ledger.position(owner.id());
        assertThat(after.pendingPaise()).isZero();
        assertThat(after.availablePaise()).isEqualTo(800);
        assertThat(after.pending()).isEmpty();
    }

    @Test
    void anOpenReportHoldsItsSessionsEarningsUntilItsResolved() throws Exception {
        var owner = payees.owner();
        var reported = payees.earn(owner.hostId(), 1_000);
        payees.earn(owner.hostId(), 500);
        var report = UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> holds.hold(report, reported.sessionId()));
        clock.advance(PAST_WINDOW);

        var held = ledger.position(owner.id());
        assertThat(held.availablePaise()).isEqualTo(400);
        assertThat(held.pendingPaise()).isEqualTo(800);
        assertThat(held.pending()).singleElement().satisfies(session -> {
            assertThat(session.sessionId()).isEqualTo(reported.sessionId());
            assertThat(session.held()).isTrue();
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> holds.release(report));
        assertThat(ledger.position(owner.id()).availablePaise()).isEqualTo(1_200);
    }

    @Test
    void thePayoutsMoneyIsInFlightBeforeRazorpayXIsAskedAndPaidOnceProcessed() throws Exception {
        var owner = payees.earningAndVerified(1_000);
        clock.advance(PAST_WINDOW);

        var outcome = payouts.create(owner.id(), "weekly", null, Paise.of(100));
        var payout = outcome.payout();
        assertThat(payout.status()).isEqualTo("created");
        assertThat(payout.grossPaise()).isEqualTo(800);
        assertThat(payout.tdsPaise())
                .as("an individual under the year's threshold")
                .isZero();
        assertThat(balance("payouts_in_flight", owner.id())).isEqualTo(800);
        assertThat(balance("host_payable", owner.hostId())).isZero();
        assertThat(RAZORPAYX.payouts())
                .noneMatch(their ->
                        payout.id().toString().equals(their.path("reference_id").asString()));

        payouts.send(payout.id());
        var sent = payouts.find(payout.id()).orElseThrow();
        assertThat(sent.status()).isEqualTo("processing");
        var their = RAZORPAYX.payout(sent.razorpayxId());
        assertThat(their.path("reference_id").asString()).isEqualTo(payout.id().toString());
        assertThat(their.path("amount").asLong()).isEqualTo(800);
        assertThat(their.path("mode").asString()).isEqualTo("UPI");
        assertThat(their.path("purpose").asString()).isEqualTo("payout");
        assertThat(their.path("narration").asString()).isEqualTo("IdleO earnings");

        // Sent again (an answer lost): the same idempotency key pays nothing twice.
        jdbc.update("update payouts.payouts set status = 'created' where id = ?", payout.id());
        payouts.send(payout.id());
        assertThat(RAZORPAYX.payouts().stream()
                        .filter(found -> payout.id()
                                .toString()
                                .equals(found.path("reference_id").asString())))
                .hasSize(1);

        var processed = RAZORPAYX.settle(sent.razorpayxId(), "processed");
        for (int i = 0; i < 10; i++) {
            payees.webhook(processed);
        }
        assertThat(payouts.find(payout.id()).orElseThrow().status()).isEqualTo("processed");
        assertThat(balance("payouts_in_flight", owner.id())).isZero();
        assertThat(balance("payouts_paid", owner.id())).isEqualTo(800);
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.ledger_entries where ref_type = 'payout_paid' and ref_id = ?",
                        Integer.class,
                        payout.id()))
                .isEqualTo(2);
        assertThat(Payees.told(jdbc, owner.id(), "payout-processed:" + payout.id()))
                .contains("left us for your UPI ID")
                .as("told once, whatever the number of events");

        // An event from before it ended, arriving late, changes nothing.
        payees.webhook(RAZORPAYX.event("payout.initiated", sent.razorpayxId()));
        assertThat(payouts.find(payout.id()).orElseThrow().status()).isEqualTo("processed");
    }

    @Test
    void aSecondPayoutWaitsForTheFirstAndTwoRunsOnOneDayAreOne() throws Exception {
        var owner = payees.earningAndVerified(2_000);
        clock.advance(PAST_WINDOW);
        paid(owner);
        payees.earn(owner.hostId(), 500);
        clock.advance(PAST_WINDOW);
        assertThat(payouts.create(owner.id(), "weekly", null, Paise.of(100)).notPaid())
                .isEqualTo(PayoutService.NotPaid.IN_PROGRESS);

        // A day nobody else's test runs on: the weekly run claims it once.
        clock.advance(Duration.ofDays(700));
        int before = jdbc.queryForObject("select count(*) from payouts.runs where kind = 'weekly'", Integer.class);
        runs.weekly();
        runs.weekly();
        assertThat(jdbc.queryForObject("select count(*) from payouts.runs where kind = 'weekly'", Integer.class))
                .isEqualTo(before + 1);
    }

    @Test
    void aFailedPayoutComesBackAndTheNextRunTriesAgain() throws Exception {
        var owner = payees.earningAndVerified(1_000);
        clock.advance(PAST_WINDOW);
        var first = paid(owner);

        payees.webhook(RAZORPAYX.settle(first.razorpayxId(), "failed"));

        var failed = payouts.find(first.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.statusReason()).isEqualTo("bank_account_closed");
        assertThat(balance("payouts_in_flight", owner.id())).isZero();
        assertThat(balance("host_payable", owner.hostId())).isEqualTo(800);
        assertThat(Payees.told(jdbc, owner.id(), "payout-failed:" + first.id())).contains("back in your earnings");

        var second = paid(owner);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.netPaise()).isEqualTo(800);
        assertThat(second.status()).isEqualTo("processing");
    }

    @Test
    void aPayoutRazorpayXRefusesComesBackAtOnce() throws Exception {
        var owner = payees.earningAndVerified(1_000);
        clock.advance(PAST_WINDOW);
        jdbc.update("update payouts.methods set fund_account_id = 'fa_nobodyknowsit' where user_id = ?", owner.id());

        var created = payouts.create(owner.id(), "weekly", null, Paise.of(100)).payout();
        assertThat(balance("payouts_in_flight", owner.id())).isEqualTo(800);
        payouts.send(created.id());

        assertThat(payouts.find(created.id()).orElseThrow().status()).isEqualTo("failed");
        assertThat(balance("payouts_in_flight", owner.id())).isZero();
        assertThat(balance("host_payable", owner.hostId())).isEqualTo(800);
    }

    @Test
    void aReversedPayoutComesBackAndWaitsForTheUpiIdToBeCheckedAgain() throws Exception {
        var vpa = "rev" + UUID.randomUUID().toString().substring(0, 6) + "@okaxis";
        var owner = payees.verified(payees.owner(), vpa);
        payees.earn(owner.hostId(), 1_000);
        clock.advance(PAST_WINDOW);
        var payout = paid(owner);
        payees.webhook(RAZORPAYX.settle(payout.razorpayxId(), "processed"));
        assertThat(balance("payouts_paid", owner.id())).isEqualTo(800);

        payees.webhook(RAZORPAYX.settle(payout.razorpayxId(), "reversed"));

        assertThat(payouts.find(payout.id()).orElseThrow().status()).isEqualTo("reversed");
        assertThat(balance("payouts_paid", owner.id())).isZero();
        assertThat(balance("host_payable", owner.hostId())).isEqualTo(800);
        assertThat(jdbc.queryForObject(
                        "select state from payouts.methods where user_id = ? order by checked_at desc limit 1",
                        String.class,
                        owner.id()))
                .isEqualTo("suspect");
        assertThat(payouts.create(owner.id(), "weekly", null, Paise.of(100)).notPaid())
                .isEqualTo(PayoutService.NotPaid.NO_METHOD);

        // The same UPI ID checked again: no change, so no hold.
        var check = payees.check(owner, vpa);
        var confirmed = payees.confirm(owner, check.get("id").asString());
        assertThat(confirmed.get("state").asString()).isEqualTo("active");
        assertThat(confirmed.get("holdUntil").isNull()).isTrue();
        assertThat(paid(owner).netPaise()).isEqualTo(800);
    }

    @Test
    void theStatusPollCatchesAWebhookThatNeverCame() throws Exception {
        var owner = payees.earningAndVerified(1_000);
        clock.advance(PAST_WINDOW);
        var payout = paid(owner);
        RAZORPAYX.quietly(payout.razorpayxId(), "processed");
        jdbc.update("update payouts.payouts set updated_at = updated_at - interval '1 hour' where id = ?", payout.id());

        payouts.poll();

        assertThat(payouts.find(payout.id()).orElseThrow().status()).isEqualTo("processed");
        assertThat(balance("payouts_paid", owner.id())).isEqualTo(800);
    }

    @Test
    void aChangedUpiIdHoldsPayoutsFor48HoursAndTellsTheHost() throws Exception {
        var owner = payees.earningAndVerified(1_000);
        clock.advance(PAST_WINDOW);
        var check = payees.check(owner, "changed" + UUID.randomUUID().toString().substring(0, 6) + "@ybl");
        var changed = payees.confirm(owner, check.get("id").asString());

        assertThat(changed.get("state").asString()).isEqualTo("active");
        var hold = java.time.Instant.parse(changed.get("holdUntil").asString());
        assertThat(hold)
                .isBetween(
                        clock.instant().plus(Duration.ofHours(48)).minusSeconds(60),
                        clock.instant().plus(Duration.ofHours(48)));
        assertThat(payouts.create(owner.id(), "weekly", null, Paise.of(100)).notPaid())
                .isEqualTo(PayoutService.NotPaid.ON_HOLD);
        assertThat(Payees.told(
                        jdbc,
                        owner.id(),
                        "payout-method-changed:" + check.get("id").asString()))
                .contains("Your payout UPI ID was changed. Not you? Contact us.");

        clock.advance(Duration.ofHours(49));
        assertThat(paid(owner).fundAccountId())
                .isEqualTo(jdbc.queryForObject(
                        "select fund_account_id from payouts.methods where id = ?",
                        String.class,
                        UUID.fromString(check.get("id").asString())));
    }

    @Test
    void confirmingAUpiIdNeedsAFreshSignIn() throws Exception {
        var owner = payees.owner();
        payees.submitPan(owner, Payees.pan(), "Asha Rao");
        var check = payees.check(owner, "fresh" + UUID.randomUUID().toString().substring(0, 6) + "@okhdfc");
        var stale = com.idleo.controlplane.TestUsers.bearer(
                owner.subject(), owner.email(), clock.instant().minus(Duration.ofMinutes(30)), Payees.LIFETIME);

        mvc.perform(post("/v1/payout-details/upi-checks/" + check.get("id").asString() + "/confirm")
                        .header("Authorization", stale))
                .andExpect(status().isForbidden());
    }

    @Test
    void aLowNameMatchGoesToAnAdminWhoseApprovalIsAudited() throws Exception {
        var owner = payees.owner();
        payees.submitPan(owner, Payees.pan(), "Asha Rao");
        var check = payees.check(owner, "lowmatch@okicici");
        assertThat(check.get("nameMatchScore").asInt()).isEqualTo(42);
        assertThat(payees.confirm(owner, check.get("id").asString())
                        .get("state")
                        .asString())
                .isEqualTo("review");
        payees.earn(owner.hostId(), 1_000);
        clock.advance(PAST_WINDOW);
        assertThat(payouts.create(owner.id(), "weekly", null, Paise.of(100)).notPaid())
                .isEqualTo(PayoutService.NotPaid.NO_METHOD);

        var admin = TestAdmins.signIn(mvc, jdbc, clock, Payees.LIFETIME);
        mvc.perform(admin.as(post("/admin/v1/payouts/verifications/upi-ids/"
                                + check.get("id").asString() + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"bank name is the PAN name with initials\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'payout_method.approve' and target_id = ?",
                        Integer.class,
                        check.get("id").asString()))
                .isEqualTo(1);
        assertThat(paid(owner).netPaise()).isEqualTo(800);
    }

    @Test
    void anInvalidUpiIdIsAFailedCheckThatCantBeConfirmed() throws Exception {
        var owner = payees.owner();
        payees.submitPan(owner, Payees.pan(), "Asha Rao");
        var check = payees.check(owner, "invalid@okicici");
        assertThat(check.get("state").asString()).isEqualTo("failed");
        assertThat(check.get("failure").asString()).contains("isn't active");

        mvc.perform(post("/v1/payout-details/upi-checks/" + check.get("id").asString() + "/confirm")
                        .header("Authorization", owner.bearer(clock)))
                .andExpect(status().isConflict());
    }

    @Test
    void aBusinessPanHasTaxDeductedFromItsFirstPayout() throws Exception {
        var owner = payees.owner();
        assertThat(payees.submitPan(owner, "ABCCE1234F", "Rao Compute LLP")
                        .get("holder")
                        .asString())
                .isEqualTo("business");
        var check = payees.check(owner, "raocompute@okicici");
        payees.confirm(owner, check.get("id").asString());
        payees.earn(owner.hostId(), 100_000);
        clock.advance(PAST_WINDOW);

        var payout = paid(owner);

        assertThat(payout.grossPaise()).isEqualTo(80_000);
        assertThat(payout.tdsPaise()).as("0.1% of ₹800").isEqualTo(80);
        assertThat(payout.netPaise()).isEqualTo(79_920);
        assertThat(payout.tdsSection()).isEqualTo("194-O");
        assertThat(jdbc.queryForObject("""
                        select coalesce(sum(amount_paise), 0) from billing.ledger_entries
                        where account = 'tds_payable' and ref_id = ?
                        """, Long.class, payout.id())).isEqualTo(80);
    }

    @Test
    void theSummaryShowsWhatsPendingAvailableAndNext() throws Exception {
        var owner = payees.owner();
        payees.earn(owner.hostId(), 1_000);

        JsonNode summary = Payees.JSON.readTree(
                mvc.perform(get("/v1/payouts/summary").header("Authorization", owner.bearer(clock)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());

        assertThat(summary.get("pendingPaise").asLong()).isEqualTo(800);
        assertThat(summary.get("availablePaise").asLong()).isZero();
        assertThat(summary.get("blockedBy").asString()).isEqualTo("no-pan");
        assertThat(summary.get("pending").get(0).get("machineName").asString()).isEqualTo("Payee laptop");
        var next = java.time.Instant.parse(summary.get("nextPayoutAt").asString())
                .atZone(java.time.ZoneId.of("Asia/Kolkata"));
        assertThat(next.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(next.getHour()).isEqualTo(10);
    }

    @Test
    void aWebhookWithoutRazorpayXsSignatureIsRefused() throws Exception {
        var owner = payees.earningAndVerified(1_000);
        clock.advance(PAST_WINDOW);
        var payout = paid(owner);
        var forged = FakeRazorpayX.resign(RAZORPAYX.settle(payout.razorpayxId(), "processed"), "not-the-secret");

        mvc.perform(post("/v1/payouts/razorpayx/webhook")
                        .header("X-Razorpay-Signature", forged.signature())
                        .header("x-razorpay-event-id", forged.eventId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forged.body()))
                .andExpect(status().isUnauthorized());
        assertThat(payouts.find(payout.id()).orElseThrow().status()).isEqualTo("processing");
    }
}
