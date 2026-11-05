package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.AccountLinks;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.razorpay.FakeRazorpay;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** The payer's keyed hash: never the UPI ID, never a plain hash, and the signal that two accounts paid from one. */
@ControlPlaneTest
class PayerHashTests {

    /** What Razorpay's documented UPI payment names as its payer (src/test/resources/razorpay). */
    private static final String VPA = "gaurav.kumar@upi";

    private static final FakeRazorpay RAZORPAY = TestRazorpayConfiguration.RAZORPAY;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcClient client;

    @Autowired
    Clock clock;

    @Autowired
    PayerHashProperties properties;

    @Autowired
    BillingAccountLinks links;

    private final JsonMapper json = JsonMapper.builder().build();

    private TestUser renter() throws Exception {
        var user = TestUsers.signUpLasting(mvc, Duration.ofDays(4));
        jdbc.update("update identity.users set created_at = created_at - interval '30 days' where id = ?", user.id());
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/v1/wallet/billing-details")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stateCode\":\"29\"}"));
        return user;
    }

    private void pays(TestUser user, String method) throws Exception {
        var answer = mvc.perform(post("/v1/wallet/topups")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":50000}"))
                .andReturn()
                .getResponse();
        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(201);
        var topUp = json.readTree(answer.getContentAsString());
        var paid = RAZORPAY.pay(topUp.get("orderId").asString(), method);
        var confirmed = mvc.perform(post("/v1/wallet/topups/" + topUp.get("id").asString() + "/confirm")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"razorpayPaymentId":"%s","razorpayOrderId":"%s","razorpaySignature":"%s"}
                                """.formatted(paid.paymentId(), paid.orderId(), paid.signature())))
                .andReturn()
                .getResponse();
        assertThat(confirmed.getStatus()).as(confirmed.getContentAsString()).isEqualTo(200);
    }

    private byte[] kept(TestUser user) {
        return jdbc.queryForObject("select hash from billing.payer_hashes where user_id = ?", byte[].class, user.id());
    }

    @Test
    void theHashIsKeyedSoThePlainHashOfTheIdDoesNotMatchIt() throws Exception {
        var user = renter();
        pays(user, "upi");

        var pepper = Base64.getDecoder().decode(properties.pepper());
        assertThat(kept(user))
                .isEqualTo(PayerHashes.hash(pepper, VPA))
                .isNotEqualTo(MessageDigest.getInstance("SHA-256").digest(VPA.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theIdItselfIsKeptNowhereInThePaymentsRow() throws Exception {
        var user = renter();
        pays(user, "upi");
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.topups t where t.user_id = ? and t::text ilike '%' || ? || '%'",
                        Integer.class, user.id(), VPA))
                .isZero();
    }

    @Test
    void twoAccountsThatPaidFromOneUpiIdAreLinkedWithoutNamingIt() throws Exception {
        var one = renter();
        var other = renter();
        pays(one, "upi");
        pays(other, "upi");

        assertThat(links.linkedTo(one.id())).anySatisfy(link -> {
            assertThat(link.otherUser()).isEqualTo(other.id());
            assertThat(link.signal()).isEqualTo("same_payer");
            assertThat(link.strength()).isEqualTo(AccountLinks.Strength.MEDIUM);
            assertThat(link.evidence()).doesNotContain(VPA);
        });
    }

    @Test
    void aCardPaymentHasNoPayerToKeep() throws Exception {
        var user = renter();
        pays(user, "card");
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.payer_hashes where user_id = ?", Integer.class, user.id()))
                .isZero();
    }

    @Test
    void withoutAPepperNothingIsKept() throws Exception {
        var user = renter();
        var topUp = jdbc.queryForObject(
                "insert into billing.topups (id, user_id, amount_paise, currency, receipt, order_id, status, created_at,"
                        + " updated_at) values (gen_random_uuid(), ?, 100, 'INR', ?, ?, 'pending', now(), now()) returning id",
                UUID.class,
                user.id(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());

        new PayerHashes(new PayerHashProperties(null, 1, null, 0), client, clock).keep(topUp, user.id(), VPA);

        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.payer_hashes where topup_id = ?", Integer.class, topUp))
                .isZero();
    }

    /** A rotation keeps old hashes under their version, and a new payment still links to them through the old pepper. */
    @Test
    void duringARotationANewPaymentIsHashedUnderBothPeppersAndStillLinks() throws Exception {
        var before = renter();
        pays(before, "upi");

        var after = renter();
        var topUp = jdbc.queryForObject(
                "insert into billing.topups (id, user_id, amount_paise, currency, receipt, order_id, status, created_at,"
                        + " updated_at) values (gen_random_uuid(), ?, 100, 'INR', ?, ?, 'pending', now(), now()) returning id",
                UUID.class,
                after.id(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        var newPepper =
                Base64.getEncoder().encodeToString("a-new-pepper-of-exactly-32-bytes".getBytes(StandardCharsets.UTF_8));
        new PayerHashes(new PayerHashProperties(newPepper, 2, properties.pepper(), 1), client, clock)
                .keep(topUp, after.id(), VPA);

        assertThat(jdbc.queryForList(
                        "select key_version from billing.payer_hashes where topup_id = ? order by key_version",
                        Integer.class,
                        topUp))
                .containsExactly(1, 2);
        assertThat(links.linkedTo(after.id()))
                .anySatisfy(link -> assertThat(link.otherUser()).isEqualTo(before.id()));
    }
}
