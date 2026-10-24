package com.idleo.controlplane.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.FinancialYear;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.razorpay.FakeRazorpayX;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RazorpayX against our payouts (ADR 0040): each kind of disagreement is found and kept once; one ours is merely behind
 * on is put right, not listed; a later run that finds an item right marks it fixed.
 */
@ControlPlaneTest
class PayoutReconciliationTests {

    static final FakeRazorpayX RAZORPAYX = TestRazorpayConfiguration.RAZORPAYX;

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
    PayoutReconciliation reconciliation;

    Payees payees;

    @BeforeEach
    void setUp() {
        payees = new Payees(mvc, context);
        RAZORPAYX.clock = clock;
    }

    @AfterEach
    void resetClock() {
        clock.reset();
        RAZORPAYX.clock = java.time.Clock.systemUTC();
    }

    private PayoutService.Payout sent(long charged) throws Exception {
        var owner = payees.earningAndVerified(charged);
        clock.advance(PayoutsTests.PAST_WINDOW);
        var payout = payouts.create(owner.id(), "weekly", null, Paise.of(100)).payout();
        payouts.send(payout.id());
        clock.reset();
        return payouts.find(payout.id()).orElseThrow();
    }

    private PayoutReconciliation.Run run(LocalDate day) {
        return reconciliation.run(day, AuditTrail.Actor.operator("reconciliation-test"), "the reconciliation test");
    }

    private String state(String kind, String subject) {
        return jdbc.queryForObject(
                "select state from payouts.mismatches where kind = ? and subject = ?", String.class, kind, subject);
    }

    @Test
    void eachKindOfDisagreementIsFoundOnceAndFixedWhenRight() throws Exception {
        // Payouts made "today" plus the window, on the day the reconciliation reads.
        var amountWrong = sent(1_000);
        var gone = sent(1_000);
        var behind = sent(1_000);
        var endedDifferently = sent(1_000);
        clock.advance(PayoutsTests.PAST_WINDOW);
        var day = LocalDate.ofInstant(clock.instant(), FinancialYear.INDIA);
        var outside = RAZORPAYX.outsidePayout(12_345);
        RAZORPAYX.tamper(amountWrong.razorpayxId(), 799);
        RAZORPAYX.forget(gone.razorpayxId());
        RAZORPAYX.quietly(behind.razorpayxId(), "processed");
        // Processed here, then RazorpayX says failed: not a move it documents, so an item for someone.
        payees.webhook(RAZORPAYX.settle(endedDifferently.razorpayxId(), "processed"));
        RAZORPAYX.quietly(endedDifferently.razorpayxId(), "failed");

        var first = run(day);
        var again = run(day);

        assertThat(first.failure()).isNull();
        assertThat(state("missing_ours", outside)).isEqualTo("open");
        assertThat(state("missing_theirs", gone.razorpayxId())).isEqualTo("open");
        assertThat(state("amount", amountWrong.razorpayxId())).isEqualTo("open");
        assertThat(state("status", endedDifferently.razorpayxId())).isEqualTo("open");
        assertThat(payouts.find(behind.id()).orElseThrow().status())
                .as("merely behind: moved on by the path a webhook takes")
                .isEqualTo("processed");
        assertThat(jdbc.queryForObject(
                        "select count(*) from payouts.mismatches where subject = ?",
                        Integer.class,
                        behind.razorpayxId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from payouts.mismatches where subject in (?, ?, ?, ?)",
                        Integer.class,
                        outside,
                        gone.razorpayxId(),
                        amountWrong.razorpayxId(),
                        endedDifferently.razorpayxId()))
                .as("running a day twice adds nothing")
                .isEqualTo(4);
        assertThat(again.open()).isGreaterThanOrEqualTo(4);

        RAZORPAYX.tamper(amountWrong.razorpayxId(), 800);
        run(day);
        assertThat(state("amount", amountWrong.razorpayxId())).isEqualTo("fixed");

        var accepted = jdbc.queryForObject(
                "select id from payouts.mismatches where kind = 'missing_ours' and subject = ?",
                java.util.UUID.class,
                outside);
        reconciliation.accept(accepted, "a test payout made from the dashboard", AuditTrail.Actor.operator("ops"));
        assertThat(state("missing_ours", outside)).isEqualTo("accepted");
        run(day);
        assertThat(state("missing_ours", outside)).as("accepted stays accepted").isEqualTo("accepted");
    }

    @Test
    void aPayoutBothSidesAgreeOnIsNoItem() throws Exception {
        var payout = sent(1_000);
        payees.webhook(RAZORPAYX.settle(payout.razorpayxId(), "processed"));

        run(LocalDate.ofInstant(payout.createdAt(), FinancialYear.INDIA));

        assertThat(jdbc.queryForObject(
                        "select count(*) from payouts.mismatches where subject in (?, ?)",
                        Integer.class,
                        payout.razorpayxId(),
                        payout.id().toString()))
                .isZero();
    }
}
