package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.razorpay.FakeRazorpay;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Buying credit (ADR 0039) against the fake Razorpay, which answers with Razorpay's documented shapes: credit only
 * once a captured payment for this order, amount and currency is proven, from Checkout's handler or a webhook, once,
 * whichever comes first, however often and in whatever order the events arrive.
 */
@ControlPlaneTest
class TopUpTests {

    static final FakeRazorpay RAZORPAY = TestRazorpayConfiguration.RAZORPAY;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    TopUps topUps;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** A renter older than a week, so the ordinary limits apply, whose token outlives the clock moving on. */
    private TestUser renter() throws Exception {
        var user = TestUsers.signUpLasting(mvc, Duration.ofDays(4));
        jdbc.update("update identity.users set created_at = created_at - interval '30 days' where id = ?", user.id());
        billingDetails(user, "29");
        return user;
    }

    private void billingDetails(TestUser user, String state) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/v1/wallet/billing-details")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stateCode\":\"" + state + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions order(TestUser user, long paise) throws Exception {
        return mvc.perform(post("/v1/wallet/topups")
                .header("Authorization", user.bearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountPaise\":" + paise + "}"));
    }

    private JsonNode ordered(TestUser user, long paise) throws Exception {
        return json.readTree(order(user, paise)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private ResultActions confirm(TestUser user, JsonNode topUp, FakeRazorpay.Paid paid) throws Exception {
        return mvc.perform(post("/v1/wallet/topups/" + topUp.get("id").asString() + "/confirm")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"razorpayPaymentId":"%s","razorpayOrderId":"%s","razorpaySignature":"%s"}
                        """.formatted(paid.paymentId(), paid.orderId(), paid.signature())));
    }

    private ResultActions webhook(FakeRazorpay.Signed event) throws Exception {
        return mvc.perform(post("/v1/payments/razorpay/webhook")
                .header("X-Razorpay-Signature", event.signature())
                .header("x-razorpay-event-id", event.eventId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(event.body()));
    }

    private long bought(TestUser user) {
        return jdbc.queryForObject(
                "select coalesce(sum(remaining_paise), 0) from billing.credit_lots where user_id = ? and kind = 'purchased'",
                Long.class,
                user.id());
    }

    private int credits(String paymentId) {
        return jdbc.queryForObject(
                "select count(*) from billing.ledger_entries where txn_id = ? and account = 'renter_purchased'",
                Integer.class,
                TopUps.txnId(paymentId));
    }

    private String statusOf(JsonNode topUp) {
        return jdbc.queryForObject(
                "select status from billing.topups where id = ?::uuid",
                String.class,
                topUp.get("id").asString());
    }

    @Test
    void anOrderIsRazorpaysForTheAmountInPaiseWithOurReceiptAndTopUpIdAndCheckoutsKey() throws Exception {
        var user = renter();
        var topUp = ordered(user, 50_000);

        var order = RAZORPAY.order(topUp.get("orderId").asString());
        assertThat(order.path("amount").asLong()).isEqualTo(50_000);
        assertThat(order.path("currency").asString()).isEqualTo("INR");
        assertThat(order.path("receipt").asString()).startsWith("tu_").hasSizeLessThanOrEqualTo(40);
        assertThat(order.path("notes").path("topup_id").asString())
                .isEqualTo(topUp.get("id").asString());
        assertThat(topUp.get("keyId").asString()).isEqualTo(FakeRazorpay.KEY_ID);
        assertThat(topUp.get("status").asString()).isEqualTo("pending");
        assertThat(bought(user)).isZero();
    }

    @Test
    void checkoutsPaymentIsCreditedOnlyAfterItsSignatureAndRazorpaysCaptureAreChecked() throws Exception {
        var user = renter();
        var topUp = ordered(user, 50_000);
        var paid = RAZORPAY.pay(topUp.get("orderId").asString(), "card");

        confirm(user, topUp, paid)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.method").value("card"));

        assertThat(bought(user)).isEqualTo(50_000);
        assertThat(credits(paid.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select amount_paise from billing.ledger_entries where txn_id = ? and account = 'gateway_clearing'"
                                + " and direction = 'debit'",
                        Long.class,
                        TopUps.txnId(paid.paymentId())))
                .isEqualTo(50_000);
        mvc.perform(get("/v1/wallet").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.boughtPaise").value(50_000))
                .andExpect(jsonPath("$.balancePaise").value(50_000));
    }

    @Test
    void aSignatureThatIsntRazorpaysForOurOrderCreditsNothing() throws Exception {
        var user = renter();
        var topUp = ordered(user, 20_000);
        var paid = RAZORPAY.pay(topUp.get("orderId").asString(), "upi");
        var forged = new FakeRazorpay.Paid(paid.paymentId(), paid.orderId(), "0".repeat(64));
        // Signed for another order: the signature is checked against the order we stored, never Checkout's.
        var otherOrder = new FakeRazorpay.Paid(
                paid.paymentId(),
                paid.orderId(),
                FakeRazorpay.checkoutSignature("order_someoneElse01", paid.paymentId()));

        confirm(user, topUp, forged).andExpect(jsonPath("$.type").value("/problems/payment-not-verified"));
        confirm(user, topUp, otherOrder).andExpect(jsonPath("$.type").value("/problems/payment-not-verified"));

        assertThat(bought(user)).isZero();
        assertThat(statusOf(topUp)).isEqualTo("pending");
    }

    @Test
    void theSameWebhookTenTimesAndTheHandlerTooCreditOnce() throws Exception {
        var user = renter();
        var topUp = ordered(user, 30_000);
        var paid = RAZORPAY.pay(topUp.get("orderId").asString(), "upi");
        var captured = RAZORPAY.event("payment.captured", paid.paymentId());

        for (int i = 0; i < 10; i++) {
            webhook(captured).andExpect(status().isOk());
        }
        webhook(RAZORPAY.event("order.paid", paid.paymentId())).andExpect(status().isOk());
        confirm(user, topUp, paid).andExpect(jsonPath("$.status").value("paid"));

        assertThat(bought(user)).isEqualTo(30_000);
        assertThat(credits(paid.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.payment_events where event_id = ?",
                        Integer.class,
                        captured.eventId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select credited_by from billing.topups where id = ?::uuid",
                        String.class,
                        topUp.get("id").asString()))
                .isEqualTo("webhook");
    }

    @Test
    void theHandlerAndTheWebhooksRacingCreditOnce() throws Exception {
        var user = renter();
        var topUp = ordered(user, 40_000);
        var paid = RAZORPAY.pay(topUp.get("orderId").asString(), "netbanking");
        var captured = RAZORPAY.event("payment.captured", paid.paymentId());
        var orderPaid = RAZORPAY.event("order.paid", paid.paymentId());

        try (var pool = Executors.newFixedThreadPool(6)) {
            var calls = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 2; i++) {
                calls.add(() ->
                        confirm(user, topUp, paid).andReturn().getResponse().getStatus());
                calls.add(() -> webhook(captured).andReturn().getResponse().getStatus());
                calls.add(() -> webhook(orderPaid).andReturn().getResponse().getStatus());
            }
            for (var result : pool.invokeAll(calls)) {
                assertThat(result.get()).isEqualTo(200);
            }
        }

        assertThat(bought(user)).isEqualTo(40_000);
        assertThat(credits(paid.paymentId())).isEqualTo(1);
    }

    @Test
    void aFailureAfterTheCaptureChangesNothingAndACaptureAfterAFailureIsCredited() throws Exception {
        var user = renter();
        var first = ordered(user, 10_000);
        var paid = RAZORPAY.pay(first.get("orderId").asString(), "upi");
        webhook(RAZORPAY.event("payment.captured", paid.paymentId())).andExpect(status().isOk());
        var lateFailure = RAZORPAY.fail(first.get("orderId").asString(), "upi");
        webhook(RAZORPAY.event("payment.failed", lateFailure)).andExpect(status().isOk());
        assertThat(statusOf(first)).isEqualTo("paid");

        // A first attempt fails; the second, on the same order, is captured: the order is paid after all.
        var second = ordered(user, 12_000);
        var failed = RAZORPAY.fail(second.get("orderId").asString(), "card");
        webhook(RAZORPAY.event("payment.failed", failed)).andExpect(status().isOk());
        mvc.perform(get("/v1/wallet/topups/" + second.get("id").asString()).header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.failureReason").isNotEmpty());
        var retried = RAZORPAY.pay(second.get("orderId").asString(), "upi");
        webhook(RAZORPAY.event("payment.captured", retried.paymentId())).andExpect(status().isOk());

        assertThat(statusOf(second)).isEqualTo("paid");
        assertThat(bought(user)).isEqualTo(22_000);
    }

    @Test
    void aPaymentForAnotherAmountOrCurrencyIsNeverCredited() throws Exception {
        var user = renter();
        var topUp = ordered(user, 25_000);
        var paid = RAZORPAY.pay(topUp.get("orderId").asString(), "upi");
        RAZORPAY.tamper(paid.paymentId(), 2_500, "INR");
        confirm(user, topUp, paid).andExpect(jsonPath("$.type").value("/problems/payment-not-verified"));

        RAZORPAY.tamper(paid.paymentId(), 25_000, "USD");
        confirm(user, topUp, paid).andExpect(jsonPath("$.type").value("/problems/payment-not-verified"));
        webhook(RAZORPAY.event("payment.captured", paid.paymentId())).andExpect(status().isOk());

        assertThat(bought(user)).isZero();
        assertThat(statusOf(topUp)).isEqualTo("pending");
    }

    @Test
    void anAuthorisedPaymentIsNeverCreditedUntilRazorpayCapturesIt() throws Exception {
        var user = renter();
        var topUp = ordered(user, 15_000);
        var authorised = RAZORPAY.authorize(topUp.get("orderId").asString(), "card");

        confirm(user, topUp, authorised)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
        webhook(RAZORPAY.event("payment.authorized", authorised.paymentId())).andExpect(status().isOk());
        assertThat(bought(user)).isZero();

        RAZORPAY.capture(authorised.paymentId());
        webhook(RAZORPAY.event("payment.captured", authorised.paymentId())).andExpect(status().isOk());
        assertThat(bought(user)).isEqualTo(15_000);
    }

    @Test
    void aWebhookIsCheckedAgainstItsRawBodyAndTheWebhookSecret() throws Exception {
        var user = renter();
        var topUp = ordered(user, 10_000);
        var paid = RAZORPAY.pay(topUp.get("orderId").asString(), "upi");
        var good = RAZORPAY.event("payment.captured", paid.paymentId());

        var tampered = new FakeRazorpay.Signed(
                good.body().replace("\"amount\":10000", "\"amount\":99999"), good.signature(), good.eventId());
        webhook(tampered).andExpect(status().isUnauthorized());
        webhook(FakeRazorpay.resign(good, "not-the-webhook-secret")).andExpect(status().isUnauthorized());
        webhook(new FakeRazorpay.Signed(good.body(), "", good.eventId())).andExpect(status().isUnauthorized());
        assertThat(bought(user)).isZero();

        webhook(good).andExpect(status().isOk());
        assertThat(bought(user)).isEqualTo(10_000);
    }

    @Test
    void thePreviousWebhookSecretWorksOnlyUntilItsGracePeriodEnds() {
        var now = Instant.parse("2026-11-01T10:00:00Z");
        var settings = new RazorpayProperties(
                RazorpayProperties.RAZORPAY_API,
                FakeRazorpay.KEY_ID,
                FakeRazorpay.KEY_SECRET,
                "the-new-secret",
                "the-old-secret",
                now.plus(Duration.ofHours(24)),
                Duration.ofSeconds(5));
        var body = "{\"entity\":\"event\",\"event\":\"payment.captured\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var old = com.idleo.controlplane.RazorpayWebhookSignature.hmac("the-old-secret", body);
        var fresh = com.idleo.controlplane.RazorpayWebhookSignature.hmac("the-new-secret", body);
        var during = new RazorpayWebhookController(
                settings, null, null, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        var after = new RazorpayWebhookController(
                settings, null, null, java.time.Clock.fixed(now.plus(Duration.ofDays(2)), java.time.ZoneOffset.UTC));

        assertThat(during.signed(body, fresh)).isTrue();
        assertThat(during.signed(body, old)).isTrue();
        assertThat(after.signed(body, fresh)).isTrue();
        assertThat(after.signed(body, old)).isFalse();
        assertThat(during.signed(body, com.idleo.controlplane.RazorpayWebhookSignature.hmac("a-third-secret", body)))
                .isFalse();
    }

    @Test
    void topUpsStayWithinTheLimits() throws Exception {
        var user = renter();
        order(user, 4_999).andExpect(jsonPath("$.type").value("/problems/top-up-out-of-limits"));
        order(user, 1_000_001).andExpect(jsonPath("$.type").value("/problems/top-up-out-of-limits"));

        // A day's top-ups are at most ₹10,000.
        var big = ordered(user, 900_000);
        confirm(user, big, RAZORPAY.pay(big.get("orderId").asString(), "upi")).andExpect(status().isOk());
        order(user, 200_000)
                .andExpect(jsonPath("$.type").value("/problems/top-up-out-of-limits"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("₹1,000.00 more today")));

        // An account younger than a week: at most ₹2,000 a top-up.
        var young = TestUsers.signUp(mvc);
        billingDetails(young, "27");
        order(young, 200_001).andExpect(jsonPath("$.type").value("/problems/top-up-out-of-limits"));
        order(young, 200_000).andExpect(status().isCreated());
    }

    @Test
    void boughtCreditHeldIsCappedAtTwentyFiveThousandRupees() throws Exception {
        var user = renter();
        for (int day = 0; day < 2; day++) {
            var topUp = ordered(user, 1_000_000);
            confirm(user, topUp, RAZORPAY.pay(topUp.get("orderId").asString(), "upi"))
                    .andExpect(status().isOk());
            clock.advance(Duration.ofDays(1).plusMinutes(1));
        }
        order(user, 600_000)
                .andExpect(jsonPath("$.type").value("/problems/top-up-out-of-limits"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("₹25,000.00")));
        order(user, 500_000).andExpect(status().isCreated());
    }

    @Test
    void moreThanFiveFailedPaymentsInAnHourPauseTopUpsForAnHour() throws Exception {
        var user = renter();
        for (int i = 0; i < 6; i++) {
            var topUp = ordered(user, 5_000);
            webhook(RAZORPAY.event(
                            "payment.failed", RAZORPAY.fail(topUp.get("orderId").asString(), "upi")))
                    .andExpect(status().isOk());
        }

        order(user, 5_000)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/too-many-failed-payments"))
                .andExpect(header().exists("Retry-After"));

        clock.advance(Duration.ofMinutes(61));
        order(user, 5_000).andExpect(status().isCreated());
    }

    @Test
    void anOrderLeftPendingADayIsAbandonedAndALateCaptureIsStillCredited() throws Exception {
        var user = renter();
        var topUp = ordered(user, 8_000);
        clock.advance(Duration.ofHours(25));
        topUps.abandonStale();
        assertThat(statusOf(topUp)).isEqualTo("abandoned");

        var late = RAZORPAY.pay(topUp.get("orderId").asString(), "upi");
        webhook(RAZORPAY.event("payment.captured", late.paymentId())).andExpect(status().isOk());
        assertThat(statusOf(topUp)).isEqualTo("paid");
        assertThat(bought(user)).isEqualTo(8_000);
    }

    @Test
    void theFirstTopUpWaitsForTheRentersState() throws Exception {
        var user = TestUsers.signUp(mvc);

        order(user, 10_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/billing-details-required"));
        billingDetails(user, "07");
        order(user, 10_000).andExpect(status().isCreated());
    }

    @Test
    void someoneElsesTopUpIsNotFound() throws Exception {
        var owner = renter();
        var other = renter();
        var topUp = ordered(owner, 9_000);

        mvc.perform(get("/v1/wallet/topups/" + topUp.get("id").asString()).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
    }
}
