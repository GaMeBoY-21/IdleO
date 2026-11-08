package com.idleo.controlplane.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Flag;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestFlags;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.razorpay.FakeRazorpayX;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code payouts.send} (ADR 0056): while it's paused, a payout is made but never sent to RazorpayX; it waits, created,
 * with its money in flight, and goes once payouts run again.
 */
@ControlPlaneTest
class PausedPayoutsTests {

    static final FakeRazorpayX RAZORPAYX = TestRazorpayConfiguration.RAZORPAYX;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    MutableClock clock;

    @Autowired
    PayoutService payouts;

    Payees payees;

    @BeforeEach
    void setUp() {
        payees = new Payees(mvc, context);
    }

    @AfterEach
    void runEverythingAgain() {
        clock.reset();
        TestFlags.runAll(context);
    }

    @Test
    @TestFlags.Enforces(Flag.PAYOUTS_SEND)
    void aPausedPayoutWaitsCreatedAndIsSentOncePayoutsRunAgain() throws Exception {
        var owner = payees.earningAndVerified(1_000);
        clock.advance(PayoutsTests.PAST_WINDOW);
        TestFlags.pause(context, Flag.PAYOUTS_SEND);

        var payout = payouts.create(owner.id(), "weekly", null, Paise.of(100)).payout();
        payouts.send(payout.id());
        payouts.resend();
        assertThat(payouts.find(payout.id()).orElseThrow().status()).isEqualTo("created");
        assertThat(RAZORPAYX.payouts())
                .noneMatch(their ->
                        payout.id().toString().equals(their.path("reference_id").asString()));
        assertThat(Payees.balance(
                        context.getBean(org.springframework.jdbc.core.JdbcTemplate.class),
                        "payouts_in_flight",
                        owner.id()))
                .as("its money waits in flight")
                .isEqualTo(800);

        TestFlags.runAll(context);
        clock.advance(Duration.ofMinutes(10));
        payouts.resend();
        assertThat(payouts.find(payout.id()).orElseThrow().status()).isEqualTo("processing");
        assertThat(RAZORPAYX.payouts().stream()
                        .filter(their -> payout.id()
                                .toString()
                                .equals(their.path("reference_id").asString())))
                .hasSize(1);
    }

    @Test
    @TestFlags.Enforces(Flag.MAINTENANCE)
    void razorpayXsWebhookLandsDuringMaintenance() throws Exception {
        var owner = payees.earningAndVerified(1_000);
        clock.advance(PayoutsTests.PAST_WINDOW);
        var payout = payouts.create(owner.id(), "weekly", null, Paise.of(100)).payout();
        payouts.send(payout.id());
        var sent = payouts.find(payout.id()).orElseThrow();

        TestFlags.pause(context, Flag.MAINTENANCE);
        payees.webhook(RAZORPAYX.settle(sent.razorpayxId(), "processed"));
        assertThat(payouts.find(payout.id()).orElseThrow().status()).isEqualTo("processed");
    }
}
