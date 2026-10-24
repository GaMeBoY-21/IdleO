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
import com.idleo.controlplane.billing.SessionRefunds;
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

/**
 * A renter's report decided after the session's earnings were paid out (ADR 0040): the host's share comes back from
 * their balance first, the rest is owed and recovered from their next earnings before any payout, never from their
 * UPI ID, and an admin may waive what's owed into chargeback losses.
 */
@ControlPlaneTest
class ClawbackTests {

    static final Duration PAST_WINDOW = PayoutsTests.PAST_WINDOW;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PayoutService payouts;

    @Autowired
    SessionRefunds refunds;

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

    private PayoutService.Payout paidOut(Payees.Owner owner) throws Exception {
        var payout = payouts.create(owner.id(), "weekly", null, Paise.of(100)).payout();
        payouts.send(payout.id());
        var sent = payouts.find(payout.id()).orElseThrow();
        payees.webhook(TestRazorpayConfiguration.RAZORPAYX.settle(sent.razorpayxId(), "processed"));
        return payouts.find(payout.id()).orElseThrow();
    }

    private void refund(Payees.Earned session, long paise) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status ->
                        refunds.refund(session.sessionId(), session.renterId(), UUID.randomUUID(), Paise.of(paise)));
    }

    private long balance(String account, UUID owner) {
        return Payees.balance(jdbc, account, owner);
    }

    @Test
    void aRefundAfterThePayoutComesFromTheBalanceFirst() throws Exception {
        var owner = payees.verified(
                payees.owner(), "cover" + UUID.randomUUID().toString().substring(0, 6) + "@ybl");
        var first = payees.earn(owner.hostId(), 1_000);
        clock.advance(PAST_WINDOW);
        paidOut(owner);
        payees.earn(owner.hostId(), 2_000);

        // ₹5 back to the renter: the commission (₹2) first, then ₹3 of the host's share, which was paid out.
        refund(first, 500);

        var clawback = jdbc.queryForMap("select * from billing.clawbacks where owner_id = ?", owner.id());
        assertThat(clawback.get("amount_paise")).isEqualTo(300L);
        assertThat(clawback.get("from_balance_paise")).isEqualTo(300L);
        assertThat(clawback.get("owed_paise")).isEqualTo(0L);
        assertThat(clawback.get("state")).isEqualTo("recovered");
        assertThat(balance("host_payable", owner.hostId())).isEqualTo(1_600 - 300);
        assertThat(balance("host_receivable", owner.id())).isZero();
        assertThat(Payees.told(jdbc, owner.id(), "clawback:" + clawback.get("id")))
                .contains("Nothing is taken from your bank account")
                .doesNotContain(first.renterId().toString());
    }

    @Test
    void whatTheBalanceCantCoverIsOwedAndRecoveredBeforeTheNextPayout() throws Exception {
        var owner = payees.verified(
                payees.owner(), "owe" + UUID.randomUUID().toString().substring(0, 6) + "@ybl");
        var first = payees.earn(owner.hostId(), 1_000);
        clock.advance(PAST_WINDOW);
        paidOut(owner);

        refund(first, 1_000);

        assertThat(balance("host_payable", owner.hostId()))
                .as("no machine below zero")
                .isZero();
        assertThat(balance("host_receivable", owner.id())).isEqualTo(800);
        var clawback = jdbc.queryForMap("select * from billing.clawbacks where owner_id = ?", owner.id());
        assertThat(clawback.get("owed_paise")).isEqualTo(800L);
        assertThat(clawback.get("state")).isEqualTo("owed");

        // The history labels it.
        var earnings =
                Payees.JSON.readTree(mvc.perform(get("/v1/earnings").header("Authorization", owner.bearer(clock)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(earnings.get("history").get("entries").get(0).get("label").asString())
                .startsWith("Recovered: refund for session "
                        + first.sessionId().toString().substring(0, 8) + " on ");

        payees.earn(owner.hostId(), 2_000);
        clock.advance(PAST_WINDOW);
        var next = payouts.create(owner.id(), "weekly", null, Paise.of(100)).payout();

        assertThat(next.grossPaise()).isEqualTo(1_600 - 800);
        assertThat(balance("host_receivable", owner.id())).isZero();
        assertThat(jdbc.queryForObject(
                        "select state from billing.clawbacks where owner_id = ?", String.class, owner.id()))
                .isEqualTo("recovered");
    }

    @Test
    void anAdminCanWaiveWhatsOwedIntoChargebackLosses() throws Exception {
        var owner = payees.verified(
                payees.owner(), "waive" + UUID.randomUUID().toString().substring(0, 6) + "@ybl");
        var first = payees.earn(owner.hostId(), 1_000);
        clock.advance(PAST_WINDOW);
        paidOut(owner);
        refund(first, 1_000);
        var id = jdbc.queryForObject("select id from billing.clawbacks where owner_id = ?", UUID.class, owner.id());
        long lossesBefore = Payees.losses(jdbc);

        var admin = TestAdmins.signIn(mvc, jdbc, clock, Payees.LIFETIME);
        mvc.perform(admin.as(post("/admin/v1/clawbacks/" + id + "/waive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"host had no way to know; we absorb it\"}"))
                .andExpect(status().isOk());

        assertThat(balance("host_receivable", owner.id())).isZero();
        assertThat(Payees.losses(jdbc) - lossesBefore).isEqualTo(800);
        assertThat(jdbc.queryForObject("select state from billing.clawbacks where id = ?", String.class, id))
                .isEqualTo("waived");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'clawback.waive' and target_id = ?",
                        Integer.class,
                        id.toString()))
                .isEqualTo(1);
        mvc.perform(admin.as(post("/admin/v1/clawbacks/" + id + "/waive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"twice should be refused\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aRefundBeforeThePayoutIsNoClawback() throws Exception {
        var owner = payees.owner();
        var session = payees.earn(owner.hostId(), 1_000);

        refund(session, 1_000);

        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.clawbacks where owner_id = ?", Integer.class, owner.id()))
                .isZero();
        assertThat(balance("host_payable", owner.hostId())).isZero();
    }
}
