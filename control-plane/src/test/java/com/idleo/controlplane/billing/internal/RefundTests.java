package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.razorpay.FakeRazorpay;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refunds of bought credit (ADR 0039): only to the payments it came from, newest first and split across several when
 * needed, reserved the moment an admin approves, posted out once Razorpay says processed and back in the lot if it
 * fails, once each however often Razorpay tells us; and a deletion refunds what's left and waits for it.
 */
@ControlPlaneTest
class RefundTests {

    static final FakeRazorpay RAZORPAY = TestRazorpayConfiguration.RAZORPAY;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CreditRefunds refunds;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void reset() {
        RAZORPAY.refundOutcome = "processed";
        RAZORPAY.refundsInProgress = 0;
        RAZORPAY.loseNextRefundAnswer = false;
        clock.reset();
    }

    private TestUser renter() throws Exception {
        return Buyers.renter(mvc, jdbc, Duration.ofDays(2));
    }

    /** A paid top-up; returns its payment id. */
    private String buy(TestUser user, long paise) throws Exception {
        var paymentId = Buyers.buy(mvc, RAZORPAY, user, paise);
        // Bought a minute apart, so "newest first" means something.
        clock.advance(Duration.ofMinutes(1));
        return paymentId;
    }

    private JsonNode ask(TestUser user, String body) throws Exception {
        return json.readTree(mvc.perform(post("/v1/wallet/refund-requests")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private long account(String account, UUID owner) {
        return Buyers.account(jdbc, account, owner);
    }

    private long bought(UUID user) {
        return Buyers.bought(jdbc, user);
    }

    @Test
    void askingOpensAMoneyTicketAndOneRequestAtATime() throws Exception {
        var user = renter();
        mvc.perform(post("/v1/wallet/refund-requests")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.type").value("/problems/nothing-to-refund"));
        buy(user, 20_000);

        var request = ask(user, "{\"note\":\"I bought too much\"}");
        assertThat(request.path("status").asString()).isEqualTo("requested");
        assertThat(jdbc.queryForObject(
                        "select topic from support.tickets where id = (select ticket_id from billing.credit_refunds"
                                + " where id = ?::uuid)",
                        String.class,
                        request.path("id").asString()))
                .isEqualTo("money");
        mvc.perform(post("/v1/wallet/refund-requests")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.type").value("/problems/refund-already-requested"));
        mvc.perform(get("/v1/wallet/refund-requests").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.refundablePaise").value(20_000))
                .andExpect(jsonPath("$.requests[0].ticketNumber").isNumber());
    }

    @Test
    void anApprovedRefundGoesBackToThePaymentsNewestFirstSplitAcrossThem() throws Exception {
        var user = renter();
        var older = buy(user, 30_000);
        var newer = buy(user, 50_000);
        var request = ask(user, "{\"amountPaise\":60000}");
        var admin = TestAdmins.signIn(mvc, jdbc, clock);

        mvc.perform(admin.as(post("/admin/v1/refunds/" + request.path("id").asString() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":60000,\"reason\":\"renter asked, credit unused\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("refunded"))
                .andExpect(jsonPath("$.parts.length()").value(2));

        assertThat(RAZORPAY.refundsOf(newer))
                .singleElement()
                .satisfies(refund -> assertThat(refund.path("amount").asLong()).isEqualTo(50_000));
        assertThat(RAZORPAY.refundsOf(older))
                .singleElement()
                .satisfies(refund -> assertThat(refund.path("amount").asLong()).isEqualTo(10_000));
        assertThat(bought(user.id())).isEqualTo(20_000);
        assertThat(account("refunds_pending", user.id())).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.invoices where user_id = ? and kind = 'credit_note'",
                        Integer.class,
                        user.id()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'refund.approve' and target_id = ?",
                        Integer.class,
                        user.id().toString()))
                .isOne();
    }

    @Test
    void theCreditIsReservedAtOnceAndAFailedPartComesBackOnceHoweverOftenWeAreTold() throws Exception {
        RAZORPAY.refundOutcome = "pending";
        var user = renter();
        var first = buy(user, 10_000);
        var second = buy(user, 10_000);
        var request = ask(user, "{}");
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(admin.as(post("/admin/v1/refunds/" + request.path("id").asString() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":20000,\"reason\":\"renter asked for everything\"}")))
                .andExpect(jsonPath("$.status").value("refunding"));
        // Reserved at once: out of the wallet, waiting in refunds_pending, and the wallet says so.
        assertThat(bought(user.id())).isZero();
        assertThat(account("refunds_pending", user.id())).isEqualTo(20_000);
        mvc.perform(get("/v1/wallet").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.refundingPaise").value(20_000))
                .andExpect(jsonPath("$.balancePaise").value(0));

        var processed = RAZORPAY.refundsOf(first).getFirst().path("id").asString();
        var failed = RAZORPAY.refundsOf(second).getFirst().path("id").asString();
        RAZORPAY.settleRefund(processed, "processed");
        RAZORPAY.settleRefund(failed, "failed");
        for (int i = 0; i < 3; i++) {
            for (var event : java.util.List.of(
                    RAZORPAY.refundEvent("refund.processed", processed),
                    RAZORPAY.refundEvent("refund.failed", failed))) {
                mvc.perform(post("/v1/payments/razorpay/webhook")
                                .header("X-Razorpay-Signature", event.signature())
                                .header("x-razorpay-event-id", event.eventId() + "-" + i)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(event.body()))
                        .andExpect(status().isOk());
            }
        }

        assertThat(account("refunds_pending", user.id())).isZero();
        assertThat(bought(user.id())).as("the failed part is back in its lot").isEqualTo(10_000);
        assertThat(refunds.find(UUID.fromString(request.path("id").asString()))
                        .orElseThrow()
                        .status())
                .isEqualTo("failed");
    }

    /**
     * Razorpay first still working on the part (409), then refunding it and the answer lost: the part is sent again
     * each turn with its receipt as the X-Refund-Idempotency key and the same body, and the payment is refunded once.
     */
    @Test
    void aPartIsSentWithItsReceiptAsTheIdempotencyKeyAndRefundedOnceWhateverAnswerIsLost() throws Exception {
        var user = renter();
        var payment = buy(user, 20_000);
        var request = ask(user, "{\"amountPaise\":20000}");
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        RAZORPAY.refundKeysSent.clear();
        RAZORPAY.refundsInProgress = 1;
        RAZORPAY.loseNextRefundAnswer = true;

        mvc.perform(admin.as(post("/admin/v1/refunds/" + request.path("id").asString() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":20000,\"reason\":\"renter asked, credit unused\"}")))
                .andExpect(status().isOk());
        String partStatus;
        int turns = 0;
        do {
            refunds.send();
            partStatus = jdbc.queryForObject(
                    "select status from billing.refund_parts where refund_id = ?::uuid",
                    String.class,
                    request.path("id").asString());
        } while (!partStatus.equals("processed") && ++turns < 10);

        assertThat(partStatus).isEqualTo("processed");
        assertThat(RAZORPAY.refundsInProgress).isZero();
        assertThat(RAZORPAY.loseNextRefundAnswer).isFalse();
        var receipt = jdbc.queryForObject(
                "select receipt from billing.refund_parts where refund_id = ?::uuid",
                String.class,
                request.path("id").asString());
        // Refused while in progress, made with its answer lost, then answered with the refund already made.
        assertThat(RAZORPAY.refundKeysSent).hasSizeGreaterThanOrEqualTo(3).containsOnly(receipt);
        assertThat(RAZORPAY.refundsOf(payment))
                .singleElement()
                .satisfies(refund -> assertThat(refund.path("amount").asLong()).isEqualTo(20_000));
        assertThat(bought(user.id())).isZero();
        assertThat(account("refunds_pending", user.id())).isZero();
    }

    @Test
    void anAdminCantRefundMoreThanTheBoughtCreditNotHeldAndCanDecline() throws Exception {
        var user = renter();
        buy(user, 10_000);
        var request = ask(user, "{}");
        var admin = TestAdmins.signIn(mvc, jdbc, clock);

        mvc.perform(admin.as(post("/admin/v1/refunds/" + request.path("id").asString() + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":10001,\"reason\":\"one paisa too many\"}")))
                .andExpect(jsonPath("$.type").value("/problems/action-refused"));
        mvc.perform(admin.as(post("/admin/v1/refunds/" + request.path("id").asString() + "/decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the renter asked us to stop\"}")))
                .andExpect(jsonPath("$.status").value("declined"));
        assertThat(bought(user.id())).isEqualTo(10_000);
    }

    @Test
    void aDeletionRefundsTheBoughtCreditAndWaitsUntilItIsBack() throws Exception {
        RAZORPAY.refundOutcome = "pending";
        var user = renter();
        var payment = buy(user, 15_000);

        refunds.forDeletion(user.id());
        refunds.forDeletion(user.id());
        assertThat(RAZORPAY.refundsOf(payment)).hasSize(1);
        assertThat(refunds.blockers(user.id()))
                .singleElement()
                .satisfies(blocker -> assertThat(blocker.code()).isEqualTo("refund-in-progress"));

        var refund = RAZORPAY.refundsOf(payment).getFirst().path("id").asString();
        RAZORPAY.settleRefund(refund, "processed");
        var event = RAZORPAY.refundEvent("refund.processed", refund);
        mvc.perform(post("/v1/payments/razorpay/webhook")
                        .header("X-Razorpay-Signature", event.signature())
                        .header("x-razorpay-event-id", event.eventId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event.body()))
                .andExpect(status().isOk());

        assertThat(refunds.blockers(user.id())).isEmpty();
        assertThat(bought(user.id())).isZero();
    }
}
