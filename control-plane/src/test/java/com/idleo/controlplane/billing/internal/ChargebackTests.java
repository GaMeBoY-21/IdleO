package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.razorpay.FakeRazorpay;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chargebacks (ADR 0039): a dispute freezes the renter's bought credit, the disputed payment's lot first, never what a
 * session holds; won, it all comes back; lost, the loss leaves the frozen credit, the rest is our loss, the account is
 * suspended pending review and the top-up's document gets a credit note; and every event, however often or late it
 * comes, changes a settled dispute no more.
 */
@ControlPlaneTest
class ChargebackTests {

    static final FakeRazorpay RAZORPAY = TestRazorpayConfiguration.RAZORPAY;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CreditReservations reservations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void reset() {
        clock.reset();
    }

    private TestUser renter() throws Exception {
        return Buyers.renter(mvc, jdbc, Duration.ofDays(2));
    }

    private String buy(TestUser user, long paise) throws Exception {
        var paymentId = Buyers.buy(mvc, RAZORPAY, user, paise);
        clock.advance(Duration.ofMinutes(1));
        return paymentId;
    }

    private static String disputeId() {
        return "disp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private void dispute(String event, String disputeId, String paymentId, long amount) throws Exception {
        Buyers.deliver(
                mvc, RAZORPAY.disputeEvent(event, disputeId, paymentId, amount, Instant.parse("2026-12-01T18:30:00Z")));
    }

    private long frozen(UUID user) {
        return Buyers.account(jdbc, "disputes_frozen", user);
    }

    private long lot(String paymentId) {
        return jdbc.queryForObject(
                "select l.remaining_paise from billing.credit_lots l join billing.topups t on t.id = l.source_id"
                        + " where t.payment_id = ?",
                Long.class,
                paymentId);
    }

    private String chargeback(String disputeId, String column) {
        return jdbc.queryForObject(
                "select " + column + "::text from billing.chargebacks where dispute_id = ?", String.class, disputeId);
    }

    @Test
    void aDisputeFreezesTheDisputedPaymentsCreditFirstAndWinningGivesItAllBack() throws Exception {
        var user = renter();
        var older = buy(user, 10_000);
        var newer = buy(user, 20_000);
        var disputeId = disputeId();

        dispute("payment.dispute.created", disputeId, older, 15_000);
        dispute("payment.dispute.created", disputeId, older, 15_000);
        dispute("payment.dispute.under_review", disputeId, older, 15_000);

        assertThat(frozen(user.id())).isEqualTo(15_000);
        assertThat(lot(older)).as("the disputed payment's own credit first").isZero();
        assertThat(lot(newer)).isEqualTo(15_000);
        mvc.perform(get("/v1/wallet").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.frozenPaise").value(15_000))
                .andExpect(jsonPath("$.balancePaise").value(15_000));
        assertThat(chargeback(disputeId, "status")).isEqualTo("under_review");

        dispute("payment.dispute.won", disputeId, older, 15_000);
        dispute("payment.dispute.won", disputeId, older, 15_000);

        assertThat(frozen(user.id())).isZero();
        assertThat(lot(older)).isEqualTo(10_000);
        assertThat(lot(newer)).isEqualTo(20_000);
        assertThat(chargeback(disputeId, "outcome")).isEqualTo("won");
        assertThat(chargeback(disputeId, "released_paise")).isEqualTo("15000");
        assertThat(jdbc.queryForObject("select status from identity.users where id = ?", String.class, user.id()))
                .isEqualTo("active");
    }

    @Test
    void aLostDisputeTakesTheFrozenCreditBooksTheRestAsALossAndSuspendsTheAccount() throws Exception {
        var user = renter();
        var payment = buy(user, 10_000);
        var disputeId = disputeId();
        long losses = Buyers.account(jdbc, "chargeback_losses", null);
        long clearing = Buyers.account(jdbc, "gateway_clearing", null);

        dispute("payment.dispute.created", disputeId, payment, 12_000);
        assertThat(frozen(user.id())).isEqualTo(10_000);

        dispute("payment.dispute.lost", disputeId, payment, 12_000);
        dispute("payment.dispute.lost", disputeId, payment, 12_000);
        // A late event after the end changes nothing.
        dispute("payment.dispute.under_review", disputeId, payment, 12_000);

        assertThat(frozen(user.id())).isZero();
        assertThat(Buyers.bought(jdbc, user.id())).isZero();
        assertThat(Buyers.account(jdbc, "chargeback_losses", null) - losses)
                .as("chargeback_losses is raised by debits")
                .isEqualTo(-2_000);
        assertThat(Buyers.account(jdbc, "gateway_clearing", null) - clearing).isEqualTo(12_000);
        assertThat(chargeback(disputeId, "outcome")).isEqualTo("lost");
        assertThat(chargeback(disputeId, "status")).isEqualTo("lost");
        assertThat(chargeback(disputeId, "lost_paise")).isEqualTo("12000");
        assertThat(jdbc.queryForObject("select status from identity.users where id = ?", String.class, user.id()))
                .isEqualTo("suspended");
        assertThat(jdbc.queryForObject(
                        "select total_paise from billing.invoices where user_id = ? and kind = 'credit_note'",
                        Long.class,
                        user.id()))
                .as("a credit note for the bought credit taken back")
                .isEqualTo(10_000);
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.ledger_entries where ref_id = (select id from billing.chargebacks"
                                + " where dispute_id = ?) and ref_type = 'chargeback_settle'",
                        Integer.class,
                        disputeId))
                .isEqualTo(3);
    }

    @Test
    void aFreezeNeverTakesWhatASessionHoldsAndTakesMoreOnceItsFree() throws Exception {
        var user = renter();
        var payment = buy(user, 10_000);
        var sessionId = UUID.randomUUID();
        var transactions = new TransactionTemplate(transactionManager);
        Boolean held = transactions.execute(status -> reservations.reserve(user.id(), sessionId, Paise.of(6_000)));
        assertThat(held).isTrue();
        var disputeId = disputeId();

        dispute("payment.dispute.created", disputeId, payment, 10_000);
        assertThat(frozen(user.id())).isEqualTo(4_000);

        transactions.executeWithoutResult(status -> reservations.release(sessionId));
        dispute("payment.dispute.action_required", disputeId, payment, 10_000);
        assertThat(frozen(user.id())).isEqualTo(10_000);

        // Closed after a refund in the fraud phase, nothing deducted: everything comes back.
        dispute("payment.dispute.closed", disputeId, payment, 10_000);
        assertThat(frozen(user.id())).isZero();
        assertThat(Buyers.bought(jdbc, user.id())).isEqualTo(10_000);
        assertThat(chargeback(disputeId, "outcome")).isEqualTo("won");
    }

    @Test
    void aDisputeOnAPaymentThatIsntOneOfOursFreezesNothingAndAdminsSeeTheEvidence() throws Exception {
        var user = renter();
        var payment = buy(user, 10_000);
        var ordered = Buyers.order(mvc, user, 5_000);
        var stranger = RAZORPAY.pay(ordered.orderId(), "card").paymentId();
        var ours = disputeId();
        var theirs = disputeId();

        dispute("payment.dispute.created", ours, payment, 3_000);
        dispute("payment.dispute.created", theirs, stranger, 5_000);

        assertThat(frozen(user.id())).isEqualTo(3_000);
        assertThat(chargeback(theirs, "user_id")).isNull();
        assertThat(chargeback(theirs, "frozen_paise")).isEqualTo("0");

        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var id = chargeback(ours, "id");
        mvc.perform(admin.as(get("/admin/v1/chargebacks/" + id)))
                .andExpect(jsonPath("$.disputeId").value(ours))
                .andExpect(jsonPath("$.frozenPaise").value(3_000))
                .andExpect(jsonPath("$.respondBy").value("2026-12-01T18:30:00Z"))
                .andExpect(jsonPath("$.payment.paymentId").value(payment))
                .andExpect(jsonPath("$.payment.method").value("upi"))
                .andExpect(jsonPath("$.sessions.length()").value(0));
        mvc.perform(admin.as(get("/admin/v1/chargebacks")))
                .andExpect(jsonPath("$.chargebacks[?(@.disputeId == '" + ours + "')]")
                        .exists());
    }
}
