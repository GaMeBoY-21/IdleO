package com.idleo.controlplane.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.TestRazorpayConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Deleting a host's account (ADR 0040, closing ADR 0037's block): earnings never stop the request; once the wait is
 * over, what's available is paid at once to the verified UPI ID and what's pending once its window ends, and the
 * account goes when it's there. With no UPI ID payouts may go to, the host adds one or gives the earnings up in writing.
 */
@ControlPlaneTest
class PayoutDeletionTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PayoutsPersonalData personalData;

    @Autowired
    PayoutService payouts;

    Payees payees;

    @BeforeEach
    void setUp() {
        payees = new Payees(mvc, context);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void theErasurePaysWhatsAvailableAtOnceAndWaitsForWhatsPending() throws Exception {
        var owner = payees.earningAndVerified(1_000);
        clock.advance(PayoutsTests.PAST_WINDOW);
        payees.earn(owner.hostId(), 500);

        var standing =
                Payees.JSON.readTree(mvc.perform(get("/v1/me/deletion").header("Authorization", owner.bearer(clock)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(standing.get("blockers"))
                .as("earnings never stop the request")
                .isEmpty();
        assertThat(standing.get("waits")).hasSize(2);
        assertThat(standing.get("waits").get(0).get("code").asString()).isEqualTo("final-payout");
        assertThat(standing.get("waits").get(1).get("code").asString()).isEqualTo("earnings-pending");
        assertThat(standing.get("waits").get(1).get("until").isNull()).isFalse();

        personalData.beforeErasure(owner.id());

        var finalPayout = payouts.open(owner.id()).orElseThrow();
        assertThat(finalPayout.kind()).isEqualTo("final");
        assertThat(finalPayout.netPaise()).as("₹10 earned, ₹8 the host's").isEqualTo(800);
        assertThat(codes(owner)).containsExactly("final-payout-in-progress");

        payees.webhook(TestRazorpayConfiguration.RAZORPAYX.settle(finalPayout.razorpayxId(), "processed"));
        assertThat(codes(owner)).containsExactly("earnings-pending");

        clock.advance(PayoutsTests.PAST_WINDOW);
        personalData.beforeErasure(owner.id());
        var last = payouts.open(owner.id()).orElseThrow();
        assertThat(last.netPaise()).isEqualTo(400);
        payees.webhook(TestRazorpayConfiguration.RAZORPAYX.settle(last.razorpayxId(), "processed"));

        assertThat(personalData.blockers(owner.id())).isEmpty();
        personalData.erase(owner.id(), PersonalData.ErasureStep.DE_IDENTIFY);
        assertThat(jdbc.queryForObject(
                        "select count(*) from payouts.methods where user_id = ? and vpa is not null",
                        Integer.class,
                        owner.id()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from payouts.pans where user_id = ?", Integer.class, owner.id()))
                .as("kept for the tax filings")
                .isEqualTo(1);
    }

    private java.util.List<String> codes(Payees.Owner owner) {
        return personalData.blockers(owner.id()).stream()
                .map(PersonalData.Blocker::code)
                .toList();
    }

    @Test
    void withNoUpiIdTheHostAddsOneOrGivesTheEarningsUpInWriting() throws Exception {
        var owner = payees.owner();
        payees.earn(owner.hostId(), 1_000);
        assertThat(codes(owner)).containsExactly("payout-method-needed");

        mvc.perform(post("/v1/payouts/forfeit")
                        .header("Authorization", owner.bearer(clock))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statement\":\"I give up my unpaid earnings to delete my account.\"}"))
                .andExpect(status().isOk());

        assertThat(codes(owner)).isEmpty();
        assertThat(Payees.balance(jdbc, "host_payable", owner.hostId())).isZero();
        assertThat(jdbc.queryForObject(
                        "select amount_paise from payouts.forfeits where user_id = ?", Long.class, owner.id()))
                .isEqualTo(800);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'earnings.forfeit' and target_id = ?",
                        Integer.class,
                        owner.id().toString()))
                .isEqualTo(1);
    }
}
