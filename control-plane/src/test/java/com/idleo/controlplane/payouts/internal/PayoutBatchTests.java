package com.idleo.controlplane.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.identity.AuditTrail;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The weekly payouts as one batch (ADR 0055): a preview, released once by approval, paying each host as previewed; a
 * preview that changed is refused until it's refreshed; who's left out, and why.
 */
@ControlPlaneTest
class PayoutBatchTests {

    private static final AuditTrail.Actor OPERATOR = AuditTrail.Actor.operator("payout-batch-test");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ApplicationContext context;

    @Autowired
    PayoutRuns runs;

    private Payees payees;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void payees() {
        payees = new Payees(mvc, context);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** A batch prepared now, as an admin's: only the week's own run is one a payday. */
    private PayoutRuns.Run batch() {
        return runs.prepare(runs.startNow("a batch for the test", OPERATOR));
    }

    private PayoutRuns.Item item(PayoutRuns.Run batch, UUID userId) {
        return runs.items(batch.id()).stream()
                .filter(item -> item.userId().equals(userId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void whatReleasePaysIsThePreviewItemByItemAndABatchIsReleasedOnce() throws Exception {
        var first = payees.earningAndVerified(30_000);
        var second = payees.earningAndVerified(50_000);
        clock.advance(PayoutsTests.PAST_WINDOW);
        var batch = batch();
        assertThat(batch.state()).isEqualTo("preview");
        assertThat(jdbc.queryForObject("select count(*) from payouts.payouts where run_id = ?", Long.class, batch.id()))
                .isZero();
        var previewFirst = item(batch, first.id()).quote();
        var previewSecond = item(batch, second.id()).quote();
        assertThat(previewFirst.leftOut()).isNull();
        assertThat(previewFirst.grossPaise()).isEqualTo(24_000);
        assertThat(previewFirst.netPaise())
                .isEqualTo(previewFirst.grossPaise() - previewFirst.tdsPaise() - previewFirst.feePaise());
        // The UPI ID only masked: its domain, never the name before the @.
        assertThat(previewFirst.upiMasked()).endsWith("@okicici").doesNotStartWith("host");

        var admin = TestAdmins.signIn(mvc, jdbc, clock, Duration.ofDays(3_000));
        var asked = body(mvc.perform(admin.as(post("/admin/v1/payouts/batches/" + batch.id() + "/release"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"" + batch.version() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the week's payouts, checked\"}"))
                .andExpect(status().isAccepted()));
        // Nothing is paid until it's approved.
        assertThat(jdbc.queryForObject("select count(*) from payouts.payouts where run_id = ?", Long.class, batch.id()))
                .isZero();
        TestAdmins.approve(mvc, jdbc, clock, asked.toString());

        var released = runs.find(batch.id()).orElseThrow();
        assertThat(released.state()).isEqualTo("released");
        for (var pair : java.util.List.of(
                java.util.Map.entry(first.id(), previewFirst), java.util.Map.entry(second.id(), previewSecond))) {
            var paid = jdbc.queryForMap(
                    "select gross_paise, tds_paise, fee_paise, net_paise from payouts.payouts where run_id = ? and user_id = ?",
                    batch.id(),
                    pair.getKey());
            assertThat(((Number) paid.get("gross_paise")).longValue())
                    .isEqualTo(pair.getValue().grossPaise());
            assertThat(((Number) paid.get("tds_paise")).longValue())
                    .isEqualTo(pair.getValue().tdsPaise());
            assertThat(((Number) paid.get("fee_paise")).longValue())
                    .isEqualTo(pair.getValue().feePaise());
            assertThat(((Number) paid.get("net_paise")).longValue())
                    .isEqualTo(pair.getValue().netPaise());
            assertThat(item(batch, pair.getKey()).payoutId()).isNotNull();
        }
        // What left the hosts' earnings is what the batch said it would pay them: the totals tie to the ledger.
        long inFlightOrPaid = jdbc.queryForObject(
                "select coalesce(sum(amount_paise), 0) from billing.ledger_entries e join payouts.payouts p on p.id = e.ref_id"
                        + " where p.run_id = ? and p.user_id in (?, ?) and e.ref_type = 'payout'"
                        + " and e.account = 'payouts_in_flight' and e.direction = 'credit'",
                Long.class,
                batch.id(),
                first.id(),
                second.id());
        assertThat(inFlightOrPaid).isEqualTo(previewFirst.netPaise() + previewSecond.netPaise());

        // The report for the CA: the items' CSV, each with its payout's status.
        var items = body(mvc.perform(admin.as(get("/admin/v1/payouts/batch-items")
                                .param("batch", batch.id().toString())))
                        .andExpect(status().isOk()))
                .get("items");
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).get("payoutStatus").isNull()).isFalse();

        // Released once: another release is refused, by the API and by the action itself.
        mvc.perform(admin.as(post("/admin/v1/payouts/batches/" + batch.id() + "/release"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"" + released.version() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the week's payouts, again\"}"))
                .andExpect(status().isConflict());
        assertThatThrownBy(() -> runs.release(batch.id(), UUID.randomUUID(), OPERATOR))
                .isInstanceOf(PayoutRuns.Refused.class)
                .hasMessageContaining("already released");
        long payouts =
                jdbc.queryForObject("select count(*) from payouts.payouts where run_id = ?", Long.class, batch.id());
        runs.release(
                batch.id(),
                jdbc.queryForObject("select approval_id from payouts.runs where id = ?", UUID.class, batch.id()),
                OPERATOR);
        assertThat(jdbc.queryForObject("select count(*) from payouts.payouts where run_id = ?", Long.class, batch.id()))
                .isEqualTo(payouts);
    }

    @Test
    void aPreviewThatChangedIsRefusedUntilItsRefreshed() throws Exception {
        var owner = payees.earningAndVerified(30_000);
        clock.advance(PayoutsTests.PAST_WINDOW);
        var batch = batch();
        assertThat(item(batch, owner.id()).quote().grossPaise()).isEqualTo(24_000);

        // More becomes available after the preview: release would pay what the approver didn't see.
        payees.earn(owner.hostId(), 10_000);
        clock.advance(PayoutsTests.PAST_WINDOW);
        assertThatThrownBy(() -> runs.release(batch.id(), UUID.randomUUID(), OPERATOR))
                .isInstanceOf(PayoutRuns.Refused.class)
                .hasMessageContaining("refresh it");
        assertThat(jdbc.queryForObject("select count(*) from payouts.payouts where run_id = ?", Long.class, batch.id()))
                .isZero();

        var refreshed = runs.prepare(batch);
        assertThat(refreshed.version()).isGreaterThan(batch.version());
        assertThat(item(refreshed, owner.id()).quote().grossPaise()).isEqualTo(32_000);
        runs.release(batch.id(), UUID.randomUUID(), OPERATOR);
        assertThat(jdbc.queryForObject(
                        "select gross_paise from payouts.payouts where run_id = ? and user_id = ?",
                        Long.class,
                        batch.id(),
                        owner.id()))
                .isEqualTo(32_000);
    }

    @Test
    void theBatchSaysWhoIsLeftOutAndWhy() throws Exception {
        var changed = payees.earningAndVerified(30_000);
        var little = payees.earningAndVerified(50);
        clock.advance(PayoutsTests.PAST_WINDOW);
        // A new UPI ID just before the batch: payouts wait before going to it.
        var check = payees.check(changed, "moved" + UUID.randomUUID().toString().substring(0, 8) + "@okicici");
        payees.confirm(changed, check.get("id").asString());
        var batch = runs.prepare(runs.startNow("a batch for the test", OPERATOR));
        assertThat(item(batch, changed.id()).quote().leftOut()).isEqualTo(PayoutService.NotPaid.ON_HOLD);
        assertThat(item(batch, little.id()).quote().leftOut()).isEqualTo(PayoutService.NotPaid.TOO_LITTLE);
        var admin = TestAdmins.signIn(mvc, jdbc, clock, Duration.ofDays(3_000));
        var items = body(mvc.perform(admin.as(get("/admin/v1/payouts/batch-items")
                        .param("batch", batch.id().toString()))))
                .get("items");
        var words = new java.util.ArrayList<String>();
        items.forEach(each -> {
            if (each.get("userId").asString().equals(changed.id().toString())) {
                words.add(each.get("leftOutWords").asString());
            }
        });
        assertThat(words).containsExactly("their UPI ID changed too recently");
    }
}
