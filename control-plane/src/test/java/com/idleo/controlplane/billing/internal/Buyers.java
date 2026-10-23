package com.idleo.controlplane.billing.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.razorpay.FakeRazorpay;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Renters who buy credit through the fake Razorpay, for the money-in tests (ADR 0039). */
final class Buyers {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Buyers() {}

    /**
     * A renter whose account is a month old (past the new-account limits) with their state given, signed in with a
     * token that outlives {@code lifetime} of the tests' clock moving on.
     */
    static TestUser renter(MockMvc mvc, JdbcTemplate jdbc, Duration lifetime) throws Exception {
        var user = TestUsers.signUpLasting(mvc, lifetime);
        jdbc.update("update identity.users set created_at = created_at - interval '30 days' where id = ?", user.id());
        mvc.perform(put("/v1/wallet/billing-details")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stateCode\":\"29\"}"))
                .andExpect(status().isOk());
        return user;
    }

    /** A top-up ordered and nothing more: the order's id and the top-up's. */
    record Ordered(String orderId, UUID topUpId) {}

    static Ordered order(MockMvc mvc, TestUser user, long paise) throws Exception {
        var topUp = read(mvc.perform(post("/v1/wallet/topups")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":" + paise + "}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return new Ordered(
                topUp.get("orderId").asString(), UUID.fromString(topUp.get("id").asString()));
    }

    /** A top-up paid by UPI and confirmed by Checkout's handler; returns its payment id. */
    static String buy(MockMvc mvc, FakeRazorpay razorpay, TestUser user, long paise) throws Exception {
        var ordered = order(mvc, user, paise);
        var paid = razorpay.pay(ordered.orderId(), "upi");
        mvc.perform(post("/v1/wallet/topups/" + ordered.topUpId() + "/confirm")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"razorpayPaymentId\":\"%s\",\"razorpaySignature\":\"%s\"}"
                                .formatted(paid.paymentId(), paid.signature())))
                .andExpect(jsonPath("$.status").value("paid"));
        return paid.paymentId();
    }

    /** Sends a signed event to the webhook, as Razorpay would. */
    static void deliver(MockMvc mvc, FakeRazorpay.Signed event) throws Exception {
        mvc.perform(post("/v1/payments/razorpay/webhook")
                        .header("X-Razorpay-Signature", event.signature())
                        .header("x-razorpay-event-id", event.eventId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event.body()))
                .andExpect(status().isOk());
    }

    /** What a ledger account holds for an owner (credits less debits; null: the platform's). */
    static long account(JdbcTemplate jdbc, String account, UUID owner) {
        return jdbc.queryForObject(
                "select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)"
                        + " from billing.ledger_entries where account = ? and owner_id is not distinct from ?",
                Long.class,
                account,
                owner);
    }

    /** The renter's bought credit, from their lots. */
    static long bought(JdbcTemplate jdbc, UUID user) {
        return jdbc.queryForObject(
                "select coalesce(sum(remaining_paise), 0) from billing.credit_lots where user_id = ? and kind = 'purchased'",
                Long.class,
                user);
    }

    private static JsonNode read(String body) {
        return JSON.readTree(body);
    }
}
