package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.razorpay.FakeRazorpay;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Razorpay against our ledger (ADR 0039): a day that agrees finds nothing; each kind of disagreement is one item however
 * often the day is run; a captured payment nobody credited is credited by the same check as every payment, and a
 * finished refund we hadn't heard of is settled, both still listed; an admin runs a day and accepts an item with a
 * reason. Each test is a day of its own far ahead, so only its own payments are in it, on both sides.
 */
@ControlPlaneTest
class PaymentReconciliationTests {

    static final FakeRazorpay RAZORPAY = TestRazorpayConfiguration.RAZORPAY;
    static final Duration LONG = Duration.ofDays(1_000);
    static final AuditTrail.Actor OPERATOR = AuditTrail.Actor.operator("tests");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    PaymentReconciliation reconciliation;

    @Autowired
    CreditRefunds refunds;

    @Autowired
    MeterRegistry registry;

    @AfterEach
    void reset() {
        clock.reset();
        RAZORPAY.clock = Clock.systemUTC();
        RAZORPAY.refundOutcome = "processed";
    }

    /** Moves both clocks to noon in India, {@code daysAhead} from today, and returns that day. */
    private LocalDate dayAhead(int daysAhead) {
        var day = LocalDate.now(PaymentReconciliation.INDIA).plusDays(daysAhead);
        var noon =
                day.atTime(LocalTime.NOON).atZone(PaymentReconciliation.INDIA).toInstant();
        clock.advance(Duration.between(clock.instant(), noon));
        RAZORPAY.clock = clock;
        return day;
    }

    private TestUser renter() throws Exception {
        return Buyers.renter(mvc, jdbc, LONG);
    }

    private Map<String, String> items(List<String> subjects) {
        return jdbc
                .query(
                        "select subject, kind || ':' || state as what from billing.payment_mismatches"
                                + " where subject = any(?::text[])",
                        (rs, n) -> Map.entry(rs.getString("subject"), rs.getString("what")),
                        (Object) subjects.toArray(String[]::new))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private int ledgerEntries() {
        return jdbc.queryForObject("select count(*) from billing.ledger_entries", Integer.class);
    }

    private void approveRefund(TestUser user, long paise) throws Exception {
        mvc.perform(post("/v1/wallet/refund-requests")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountPaise\":" + paise + "}"));
        var request = refunds.open(user.id()).orElseThrow();
        refunds.approve(request.id(), Paise.of(paise), "renter asked, credit unused", OPERATOR);
    }

    @Test
    void aDayThatAgreesFindsNothingAndRunningItTwiceAddsNothing() throws Exception {
        var day = dayAhead(700);
        var user = renter();
        Buyers.buy(mvc, RAZORPAY, user, 10_000);
        var refunded = Buyers.buy(mvc, RAZORPAY, user, 20_000);
        approveRefund(user, 5_000);
        assertThat(RAZORPAY.refundsOf(refunded)).hasSize(1);

        var first = reconciliation.run(day, OPERATOR, "checking a quiet day");
        int entries = ledgerEntries();
        var second = reconciliation.run(day, OPERATOR, "and once more");

        assertThat(first.failure()).isNull();
        assertThat(first.payments()).isEqualTo(2);
        assertThat(first.refunds()).isEqualTo(1);
        assertThat(first.found()).isZero();
        assertThat(second.found()).isZero();
        assertThat(ledgerEntries()).isEqualTo(entries);
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.payment_mismatches where user_id = ?", Integer.class, user.id()))
                .isZero();
    }

    @Test
    void eachKindOfDisagreementIsOneItemHoweverOftenTheDayIsRun() throws Exception {
        var day = dayAhead(701);
        var user = renter();
        var forgotten = Buyers.buy(mvc, RAZORPAY, user, 5_000);
        RAZORPAY.forget(forgotten);
        var tampered = Buyers.buy(mvc, RAZORPAY, user, 5_000);
        RAZORPAY.tamper(tampered, 4_000, "INR");
        var failed = Buyers.buy(mvc, RAZORPAY, user, 5_000);
        RAZORPAY.payment(failed).put("status", "failed");
        var uncredited = Buyers.order(mvc, user, 6_000);
        var captured = RAZORPAY.pay(uncredited.orderId(), "upi").paymentId();
        var authorised = RAZORPAY.authorize(Buyers.order(mvc, user, 7_000).orderId(), "card")
                .paymentId();
        var outside = RAZORPAY.pay(RAZORPAY.outsideOrder(8_000), "card").paymentId();
        var subjects = List.of(forgotten, tampered, failed, captured, authorised, outside);

        var run = reconciliation.run(day, OPERATOR, "a day with everything wrong");

        assertThat(run.found()).isEqualTo(6);
        assertThat(items(subjects))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        forgotten, "missing_theirs:open",
                        tampered, "amount:open",
                        failed, "status:open",
                        captured, "captured_not_credited:open",
                        authorised, "authorized_not_captured:open",
                        outside, "missing_ours:open"));
        assertThat(jdbc.queryForObject(
                        "select status || ':' || credited_by from billing.topups where id = ?",
                        String.class,
                        uncredited.topUpId()))
                .as("credited by the same check as every payment")
                .isEqualTo("paid:reconciliation");
        assertThat(jdbc.queryForObject(
                        "select status from billing.topups where payment_id is null and user_id = ? and amount_paise = 7000",
                        String.class,
                        user.id()))
                .as("an authorised payment is never credited")
                .isEqualTo("pending");
        long open =
                jdbc.queryForObject("select count(*) from billing.payment_mismatches where state = 'open'", Long.class);
        assertThat(registry.get("billing.payments.reconciliation.open").gauge().value())
                .isEqualTo((double) open);

        int entries = ledgerEntries();
        int rows = jdbc.queryForObject("select count(*) from billing.payment_mismatches", Integer.class);
        reconciliation.run(day, OPERATOR, "the same day again");

        assertThat(ledgerEntries()).as("nothing credited twice").isEqualTo(entries);
        assertThat(jdbc.queryForObject("select count(*) from billing.payment_mismatches", Integer.class))
                .isEqualTo(rows);
        assertThat(items(subjects))
                .as("the credited payment is right now; the rest still aren't")
                .containsEntry(captured, "captured_not_credited:fixed")
                .containsEntry(tampered, "amount:open")
                .containsEntry(outside, "missing_ours:open");
    }

    @Test
    void aRefundFinishedAtRazorpayThatWeHadntHeardOfIsSettledAndListed() throws Exception {
        var day = dayAhead(702);
        RAZORPAY.refundOutcome = "pending";
        var user = renter();
        var payment = Buyers.buy(mvc, RAZORPAY, user, 10_000);
        approveRefund(user, 10_000);
        var refund = RAZORPAY.refundsOf(payment).getFirst().path("id").asString();
        RAZORPAY.settleRefund(refund, "processed");

        reconciliation.run(day, OPERATOR, "the refund webhook was lost");

        assertThat(items(List.of(refund))).containsEntry(refund, "status:open");
        assertThat(jdbc.queryForObject(
                        "select status from billing.refund_parts where razorpay_refund_id = ?", String.class, refund))
                .isEqualTo("processed");
        assertThat(Buyers.account(jdbc, "refunds_pending", user.id())).isZero();
    }

    @Test
    void anAdminRunsADayAndAcceptsAnItemWithAReason() throws Exception {
        var day = dayAhead(703);
        var user = renter();
        var authorised = RAZORPAY.authorize(Buyers.order(mvc, user, 5_000).orderId(), "card")
                .paymentId();
        var admin = TestAdmins.signIn(mvc, jdbc, clock, LONG);

        mvc.perform(admin.as(post("/admin/v1/payments/reconciliation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"" + day.plusDays(1) + "\",\"reason\":\"checking tomorrow\"}")))
                .andExpect(jsonPath("$.type").value("/problems/action-refused"));
        mvc.perform(admin.as(post("/admin/v1/payments/reconciliation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"checking today by hand\"}")))
                .andExpect(jsonPath("$.day").value(day.toString()))
                .andExpect(jsonPath("$.found").value(1));
        var id = jdbc.queryForObject(
                "select id from billing.payment_mismatches where subject = ?", UUID.class, authorised);
        mvc.perform(admin.as(get("/admin/v1/payments/reconciliation")))
                .andExpect(jsonPath("$.mismatches[?(@.subject == '" + authorised + "')].kind")
                        .value("authorized_not_captured"));

        mvc.perform(admin.as(post("/admin/v1/payments/mismatches/" + id + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Razorpay refunds it by itself after five days\"}")))
                .andExpect(jsonPath("$.state").value("accepted"));
        mvc.perform(admin.as(post("/admin/v1/payments/reconciliation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"checking today again\"}")))
                .andExpect(jsonPath("$.found").value(1));

        assertThat(items(List.of(authorised))).containsEntry(authorised, "authorized_not_captured:accepted");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'payments.mismatch.accept'"
                                + " and target_id = ?",
                        Integer.class,
                        id.toString()))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'payments.reconcile'"
                                + " and actor_user_id = ?",
                        Integer.class,
                        admin.id()))
                .isEqualTo(2);
    }
}
