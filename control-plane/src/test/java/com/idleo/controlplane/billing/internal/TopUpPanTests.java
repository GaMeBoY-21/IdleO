package com.idleo.controlplane.billing.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.FinancialYear;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A renter who has bought more than {@code legal.payouts.renter-pan-above-paise} (₹2,00,000) this financial year gives a
 * verified PAN before buying more (ADR 0040); a PAN they've given unlocks it.
 */
@ControlPlaneTest
class TopUpPanTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Test
    void pastTheYearsAmountATopUpNeedsAVerifiedPan() throws Exception {
        var renter = Buyers.renter(mvc, jdbc, Duration.ofHours(1));
        // ₹1,99,950 bought earlier this financial year (not today, so the day's limit doesn't apply).
        var earlier = FinancialYear.of(clock.instant()).start().plus(Duration.ofDays(1));
        var topUp = UUID.randomUUID();
        jdbc.update(
                """
                insert into billing.topups
                    (id, user_id, amount_paise, currency, receipt, order_id, status, payment_id, txn_id, created_at,
                     updated_at, paid_at)
                values (?, ?, 19995000, 'INR', ?, ?, 'paid', ?, ?, ?, ?, ?)
                """,
                topUp,
                renter.id(),
                "tu_" + topUp.toString().replace("-", "").substring(0, 20),
                "order_pan" + topUp.toString().substring(0, 8),
                "pay_pan" + topUp.toString().substring(0, 8),
                UUID.randomUUID(),
                Timestamp.from(earlier),
                Timestamp.from(earlier),
                Timestamp.from(earlier));

        Buyers.order(mvc, renter, 5_000);
        mvc.perform(post("/v1/wallet/topups")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":5100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/pan-required"));

        mvc.perform(put("/v1/payout-details/pan")
                        .header("Authorization", renter.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pan\":\"ABCPR4321K\",\"name\":\"Ravi Kumar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("verified"));
        Buyers.order(mvc, renter, 5_100);
    }
}
