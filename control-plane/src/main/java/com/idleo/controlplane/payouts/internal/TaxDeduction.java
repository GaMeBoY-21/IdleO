package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.FinancialYear;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.Paise;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tax deducted at source from a payout (ADR 0040), every number from {@code legal.tds}, which the CA confirms:
 *
 * <ul>
 *   <li>The basis is the gross the person was paid in the financial year (April to March): what left their machines'
 *       earnings for payouts that didn't come back, this one included.
 *   <li>A resident individual (or family) with a verified PAN: nothing while that stays at or under {@code
 *       individual-threshold-paise}; once it's over, the rate on the whole year's gross, less what was deducted before
 *       in the year, so the year's deduction always comes to the rate on the year's gross.
 *   <li>A business with a verified PAN: the rate on the year's gross, less what was deducted before.
 *   <li>No verified PAN (only if {@code pay-without-pan}): the no-PAN rate on this payout.
 * </ul>
 *
 * Rounded to the nearest paisa, half up. The section's name is recorded on each payout, never written in code.
 */
@Component
class TaxDeduction {

    /** A payout's deduction, and the section and rate it was made under. */
    record Deduction(Paise tds, String section, int basisPoints) {}

    /** What a payout that came back no longer counts. */
    static final List<String> RETURNED = List.of("failed", "rejected", "cancelled", "reversed");

    private final JdbcClient jdbc;
    private final LegalSettings legal;

    TaxDeduction(JdbcClient jdbc, LegalSettings legal) {
        this.jdbc = jdbc;
        this.legal = legal;
    }

    /**
     * @param pan the person's verified PAN, or null when they have none
     */
    Deduction of(UUID userId, Paise gross, Instant at, Pans.@Nullable Pan pan) {
        var tds = legal.tds();
        if (pan == null) {
            return new Deduction(
                    rate(gross.value(), tds.noPanBasisPoints()), tds.noPanSection(), tds.noPanBasisPoints());
        }
        var year = FinancialYear.of(at);
        var before = jdbc.sql("""
                        select coalesce(sum(gross_paise), 0) as gross, coalesce(sum(tds_paise), 0) as tds
                        from payouts.payouts
                        where user_id = :userId and financial_year = :year and status not in (:returned)
                          and created_at < :at
                        """)
                .param("userId", userId)
                .param("year", year.name())
                .param("returned", RETURNED)
                .param("at", Timestamp.from(at))
                .query((rs, n) -> new long[] {rs.getLong("gross"), rs.getLong("tds")})
                .single();
        long yearGross = Math.addExact(before[0], gross.value());
        if (pan.individual() && yearGross <= tds.individualThresholdPaise()) {
            return new Deduction(Paise.ZERO, tds.section(), tds.basisPoints());
        }
        long owed = rate(yearGross, tds.basisPoints()).value() - before[1];
        // Never more than this payout's gross, and never negative.
        return new Deduction(Paise.of(Math.max(0, Math.min(owed, gross.value()))), tds.section(), tds.basisPoints());
    }

    /** {@code paise} at {@code basisPoints} (10 is 0.1%), to the nearest paisa, half up. */
    static Paise rate(long paise, int basisPoints) {
        return Paise.of(Math.addExact(Math.multiplyExact(paise, basisPoints), 5_000) / 10_000);
    }
}
