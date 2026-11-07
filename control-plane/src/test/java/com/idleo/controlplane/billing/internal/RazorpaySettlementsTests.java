package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.razorpay.FakeRazorpay;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
 * Razorpay's settlements against the ledger (ADR 0055), on the fake's settlement fixtures: a settlement that matches is
 * posted once; a fee we didn't expect and an item we have no record of are found, and resolved, one by an adjustment
 * that names it and one as a known difference; a payment no settlement carried is missing on theirs until one does.
 */
@ControlPlaneTest
class RazorpaySettlementsTests {

    static final FakeRazorpay RAZORPAY = TestRazorpayConfiguration.RAZORPAY;
    private static final AuditTrail.Actor OPERATOR = AuditTrail.Actor.operator("settlements-test");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RazorpaySettlements settlements;

    @Autowired
    Adjustments adjustments;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void reset() {
        clock.reset();
        RAZORPAY.clock = Clock.systemUTC();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String paid(long paise) throws Exception {
        return Buyers.buy(mvc, RAZORPAY, Buyers.renter(mvc, jdbc, Duration.ofDays(30)), paise);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), Periods.INDIA);
    }

    private JsonNode item(String entityId) {
        var row = jdbc.queryForMap(
                "select id, match, state, settlement_id from billing.settlement_items where entity_id = ?"
                        + " order by found_at desc limit 1",
                entityId);
        return json.valueToTree(row);
    }

    @Test
    void aSettlementThatMatchesIsPostedOnceWithItsFeesAndTheirTax() throws Exception {
        var first = paid(50_000);
        var second = paid(30_000);
        var settlement = RAZORPAY.settle(clock.instant(), 200, List.of(first, second), List.of());

        var fetched = settlements.fetch(today());
        assertThat(fetched.settlements()).isPositive();
        var row = jdbc.queryForMap("select * from billing.settlements where id = ?", settlement);
        assertThat(row.get("state")).isEqualTo("matched");
        assertThat(item(first).get("match").asString()).isEqualTo("matched");
        assertThat(item(second).get("match").asString()).isEqualTo("matched");

        // Posted once: the bank, Razorpay's fees and their GST, out of clearing.
        var txn = (UUID) row.get("txn_id");
        assertThat(txn).isNotNull();
        long fees = 1_000 + 600;
        long tax = Math.round(1_000 * 0.18) + Math.round(600 * 0.18);
        assertThat(leg(txn, "bank", "debit")).isEqualTo(80_000 - fees - tax);
        assertThat(leg(txn, "gateway_fees", "debit")).isEqualTo(fees);
        assertThat(leg(txn, "gst_input_credit", "debit")).isEqualTo(tax);
        assertThat(leg(txn, "gateway_clearing", "credit")).isEqualTo(80_000);
        settlements.fetch(today());
        assertThat(jdbc.queryForObject("select count(*) from billing.ledger_entries where txn_id = ?", Long.class, txn))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.settlement_items where settlement_id = ?",
                        Long.class,
                        settlement))
                .isEqualTo(2);
    }

    private long leg(UUID txn, String account, String direction) {
        return jdbc.queryForObject(
                "select amount_paise from billing.ledger_entries where txn_id = ? and account = ? and direction = ?",
                Long.class,
                txn,
                account,
                direction);
    }

    @Test
    void aFeeWeDidntExpectAndAnItemWeDontHaveAreFoundThenResolved() throws Exception {
        var payment = paid(40_000);
        // Razorpay kept 3% where we expect 2%, and settled a payment we've never heard of.
        var settlement = RAZORPAY.settle(clock.instant(), 300, List.of(payment), List.of());
        RAZORPAY.addSettlementItem(settlement, "payment", "pay_strangers1", 5_000, 0, 100, 18, "pay_strangers1");
        settlements.fetch(today());

        var fee = item(payment);
        var stranger = item("pay_strangers1");
        assertThat(fee.get("match").asString()).isEqualTo("unexpected_fee");
        assertThat(stranger.get("match").asString()).isEqualTo("missing_ours");
        assertThat(jdbc.queryForObject("select state from billing.settlements where id = ?", String.class, settlement))
                .isEqualTo("unmatched");

        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var open = body(mvc.perform(admin.as(get("/admin/v1/settlement-items")
                                .param("settlement", settlement)
                                .param("state", "open")))
                        .andExpect(status().isOk()))
                .get("items");
        assertThat(open).hasSize(2);

        // The fee is put right by an approved adjustment that names the item; the stranger is a known difference.
        var feeItem = UUID.fromString(fee.get("id").asString());
        var correction = adjustments.post(
                UUID.randomUUID(),
                new Adjustments.Request(
                        Adjustments.Template.FEE_CORRECTION,
                        List.of(
                                new Adjustments.Leg(
                                        Account.GATEWAY_FEES,
                                        null,
                                        com.idleo.controlplane.Paise.of(400),
                                        Direction.DEBIT),
                                new Adjustments.Leg(
                                        Account.GATEWAY_CLEARING,
                                        null,
                                        com.idleo.controlplane.Paise.of(400),
                                        Direction.CREDIT)),
                        "Razorpay's fee above our rate",
                        "settlement item " + feeItem,
                        null),
                OPERATOR);
        mvc.perform(admin.as(post("/admin/v1/settlement-items/" + feeItem + "/resolve"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"adjustment\",\"adjustmentId\":\"" + correction.id()
                                + "\",\"reason\":\"the fee correction is posted\"}"))
                .andExpect(status().isOk());
        var strangerItem = stranger.get("id").asString();
        mvc.perform(
                        admin.as(post("/admin/v1/settlement-items/" + strangerItem + "/resolve"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"resolution\":\"known_difference\",\"reason\":\"Razorpay's test payment, not ours\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.settlement_items where settlement_id = ? and state = 'open'",
                        Long.class,
                        settlement))
                .isZero();
        assertThat(jdbc.queryForObject("select state from billing.settlements where id = ?", String.class, settlement))
                .isEqualTo("matched");
        assertThat(jdbc.queryForObject(
                        "select adjustment_id from billing.settlement_items where id = ?", UUID.class, feeItem))
                .isEqualTo(correction.id());
        // Each resolution is audited.
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'settlement_item.resolve' and target_id = ?",
                        Long.class,
                        strangerItem))
                .isOne();
    }

    @Test
    void aPaymentNoSettlementCarriedIsMissingOnTheirsUntilOneDoes() throws Exception {
        var payment = paid(20_000);
        clock.advance(Duration.ofDays(4));
        RAZORPAY.clock = clock;
        settlements.fetch(today());
        var missing = item(payment);
        assertThat(missing.get("match").asString()).isEqualTo("missing_theirs");
        assertThat(missing.get("state").asString()).isEqualTo("open");
        assertThat(missing.get("settlement_id").isNull()).isTrue();

        RAZORPAY.settle(clock.instant(), 200, List.of(payment), List.of());
        settlements.fetch(today());
        assertThat(jdbc.queryForObject(
                        "select resolution from billing.settlement_items where entity_id = ? and settlement_id is null",
                        String.class,
                        payment))
                .isEqualTo("settled_later");
    }
}
