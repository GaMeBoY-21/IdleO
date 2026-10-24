package com.idleo.controlplane.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PANs (ADR 0040): the shape is checked here, the rest by the PAN check (the fake in tests); a PAN is sealed and shown
 * by its last four characters only; an admin confirms one in review, or reads it whole, with a reason, audited.
 */
@ControlPlaneTest
class PanTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    Payees payees;

    @BeforeEach
    void setUp() {
        payees = new Payees(mvc, context);
    }

    private org.springframework.test.web.servlet.ResultActions submit(Payees.Owner owner, String pan, String name)
            throws Exception {
        return mvc.perform(put("/v1/payout-details/pan")
                .header("Authorization", owner.bearer(clock))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pan\":\"" + pan + "\",\"name\":\"" + name + "\"}"));
    }

    @Test
    void aPanOfTheWrongShapeIsRefused() throws Exception {
        var owner = payees.owner();
        submit(owner, "ABCDE1234", "Asha Rao")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        submit(owner, "ABCGE1234F", "Asha Rao").andExpect(status().isBadRequest());
        submit(owner, "ABCPE1234F", "Asha <script>").andExpect(status().isBadRequest());
    }

    @Test
    void aPanIsSealedAndShownByItsLastFour() throws Exception {
        var owner = payees.owner();
        submit(owner, "abcpe1234f", "  Asha   Rao ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last4").value("234F"))
                .andExpect(jsonPath("$.name").value("Asha Rao"))
                .andExpect(jsonPath("$.holder").value("individual"))
                .andExpect(jsonPath("$.state").value("verified"));

        var stored =
                jdbc.queryForObject("select pan_sealed from payouts.pans where user_id = ?", byte[].class, owner.id());
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("ABCPE1234F");
        mvc.perform(get("/v1/payout-details").header("Authorization", owner.bearer(clock)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pan.last4").value("234F"));
        assertThat(mvc.perform(get("/v1/payout-details").header("Authorization", owner.bearer(clock)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain("ABCPE1234F");
        submit(owner, "ABCPE5678F", "Asha Rao")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/pan-already-verified"));
    }

    @Test
    void noSuchPanIsRejectedAndAMismatchedNameGoesToAnAdmin() throws Exception {
        var owner = payees.owner();
        submit(owner, "ABCPE0000F", "Asha Rao")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("rejected"));
        submit(owner, "ABCPE9999F", "Asha Rao")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("review"));
        var check = mvc.perform(post("/v1/payout-details/upi-checks")
                        .header("Authorization", owner.bearer(clock))
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vpa\":\"asha@okicici\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/pan-not-verified"));

        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(admin.as(get("/admin/v1/payouts/verifications"))).andExpect(status().isOk());
        mvc.perform(admin.as(post("/admin/v1/payouts/verifications/pans/" + owner.id() + "/verify"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"name on the card matches with a middle name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("verified"));
        var revealed = mvc.perform(admin.as(post("/admin/v1/payouts/verifications/pans/" + owner.id() + "/reveal"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"checking the tax filing for the CA\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(revealed).contains("ABCPE9999F");
        assertThat(jdbc.queryForList(
                        "select action from identity.audit_log where target_id = ? order by created_at",
                        String.class,
                        owner.id().toString()))
                .containsSubsequence("pan.verify", "pan.reveal");
        assertThat(check).isNotNull();
    }

    @Test
    void aFewUpiChecksADayAreAllowed() throws Exception {
        var owner = payees.owner();
        payees.submitPan(owner, Payees.pan(), "Asha Rao");
        for (int i = 0; i < 5; i++) {
            payees.check(owner, "try" + i + "@okicici");
        }
        mvc.perform(post("/v1/payout-details/upi-checks")
                        .header("Authorization", owner.bearer(clock))
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vpa\":\"sixth@okicici\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/too-many-upi-checks"));
    }
}
