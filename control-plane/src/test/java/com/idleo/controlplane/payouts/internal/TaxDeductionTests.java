package com.idleo.controlplane.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.FinancialYear;
import com.idleo.controlplane.Paise;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tax deducted at source (ADR 0040), every number from {@code legal.tds}: nothing for an individual with a PAN until
 * the year's gross passes ₹5,00,000, then 0.1% of the whole year's gross less what was deducted before; 0.1% from the
 * first rupee for a business; 5% of the payout with no PAN; and a new financial year starts again.
 */
@ControlPlaneTest
class TaxDeductionTests {

    static final long LAKH = 100_000L * 100;

    @Autowired
    TaxDeduction tax;

    @Autowired
    JdbcTemplate jdbc;

    private static Pans.Pan pan(UUID userId, String holder) {
        return new Pans.Pan(
                userId,
                "234F",
                "Asha Rao",
                holder,
                "verified",
                "verified",
                "fake",
                Instant.now(),
                Instant.now(),
                "fake",
                null);
    }

    /** A payout already made to the person, as the year's basis sees it. */
    private void paid(UUID userId, long gross, long tds, String status, Instant at) {
        var method = UUID.randomUUID();
        jdbc.update("""
                insert into payouts.methods (id, user_id, vpa, state, fund_account_id, checked_at)
                values (?, ?, 'tax@okicici', 'replaced', 'fa_tax', ?)
                """, method, userId, Timestamp.from(at));
        jdbc.update(
                """
                insert into payouts.payouts
                    (id, user_id, kind, method_id, fund_account_id, gross_paise, tds_paise, fee_paise, net_paise,
                     tds_section, tds_basis_points, financial_year, status, created_at, updated_at)
                values (?, ?, 'weekly', ?, 'fa_tax', ?, ?, 0, ?, '194-O', 10, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                userId,
                method,
                gross,
                tds,
                gross - tds,
                FinancialYear.of(at).name(),
                status,
                Timestamp.from(at),
                Timestamp.from(at));
    }

    private static Instant on(int year, int month, int day) {
        return LocalDate.of(year, month, day)
                .atTime(12, 0)
                .atZone(FinancialYear.INDIA)
                .toInstant();
    }

    @Test
    void anIndividualWithAPanPaysNothingUpToTheThresholdThenTheRateOnTheWholeYear() {
        var userId = UUID.randomUUID();
        var pan = pan(userId, "individual");
        var at = on(2026, 11, 2);
        paid(userId, 4 * LAKH, 0, "processed", on(2026, 6, 1));

        assertThat(tax.of(userId, Paise.of(LAKH), at, pan).tds())
                .as("exactly ₹5,00,000 in the year")
                .isEqualTo(Paise.ZERO);

        paid(userId, LAKH, 0, "processed", on(2026, 10, 1));
        var crossing = tax.of(userId, Paise.of(2_000_00), at, pan);
        assertThat(crossing.tds()).as("0.1% of ₹5,02,000").isEqualTo(Paise.of(50_200));
        assertThat(crossing.section()).isEqualTo("194-O");
        assertThat(crossing.basisPoints()).isEqualTo(10);

        paid(userId, 2_000_00, 50_200, "processed", on(2026, 10, 20));
        assertThat(tax.of(userId, Paise.of(1_000_00), at, pan).tds())
                .as("0.1% of ₹5,03,000, less the ₹502 deducted")
                .isEqualTo(Paise.of(100));
    }

    @Test
    void aPayoutThatCameBackDoesntCount() {
        var userId = UUID.randomUUID();
        paid(userId, 5 * LAKH, 0, "reversed", on(2026, 7, 1));
        paid(userId, 5 * LAKH, 0, "failed", on(2026, 7, 2));

        assertThat(tax.of(userId, Paise.of(LAKH), on(2026, 11, 2), pan(userId, "individual"))
                        .tds())
                .isEqualTo(Paise.ZERO);
    }

    @Test
    void theThresholdStartsAgainEachFinancialYear() {
        var userId = UUID.randomUUID();
        var pan = pan(userId, "individual");
        paid(userId, 5 * LAKH, 0, "processed", on(2027, 3, 30));

        assertThat(tax.of(userId, Paise.of(LAKH), on(2027, 3, 31), pan).tds())
                .as("31 March: still 2026-27, over ₹5,00,000")
                .isEqualTo(Paise.of(6 * LAKH / 1_000));
        assertThat(tax.of(userId, Paise.of(LAKH), on(2027, 4, 1), pan).tds())
                .as("1 April: 2027-28's first payout")
                .isEqualTo(Paise.ZERO);
    }

    @Test
    void aBusinessPaysTheRateFromTheFirstRupee() {
        var userId = UUID.randomUUID();
        assertThat(tax.of(userId, Paise.of(1_000_00), on(2026, 11, 2), pan(userId, "business"))
                        .tds())
                .isEqualTo(Paise.of(100));
    }

    @Test
    void withoutAPanTheNoPanRateIsDeductedFromThePayout() {
        var userId = UUID.randomUUID();
        var deduction = tax.of(userId, Paise.of(1_000_00), on(2026, 11, 2), null);

        assertThat(deduction.tds()).as("5% of ₹1,000").isEqualTo(Paise.of(50_00));
        assertThat(deduction.section()).isEqualTo("206AA");
        assertThat(deduction.basisPoints()).isEqualTo(500);
    }

    @Test
    void theRateRoundsToTheNearestPaisaHalfUp() {
        assertThat(TaxDeduction.rate(4, 10)).isEqualTo(Paise.ZERO);
        assertThat(TaxDeduction.rate(500, 10)).isEqualTo(Paise.of(1));
        assertThat(TaxDeduction.rate(1_499, 10)).isEqualTo(Paise.of(1));
        assertThat(TaxDeduction.rate(1_500, 10)).isEqualTo(Paise.of(2));
    }
}
