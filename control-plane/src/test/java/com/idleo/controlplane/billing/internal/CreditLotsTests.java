package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.SessionCharges;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credit in lots (ADR 0039): free credit is spent first, the soonest to expire first, then bought credit, the oldest
 * first; a charge records which lots it drew from; free credit expires but never what a hold needs; lost time goes back
 * to the lots it came from; a report's give-back never expires; and the nightly check proves every lot.
 */
@ControlPlaneTest
class CreditLotsTests {

    @Autowired
    Ledger ledger;

    @Autowired
    SessionCharges charges;

    @Autowired
    CreditReservationService reservations;

    @Autowired
    FreeCreditExpiry expiry;

    @Autowired
    WalletReconciliation reconciliation;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** Free credit, into a lot of its own. */
    private CreditLot free(UUID renter, long paise, @Nullable Instant expires) {
        var source = UUID.randomUUID();
        var lot = ledger.openLot(renter, CreditLot.Kind.FREE, "credit_grant", source, Paise.of(paise), expires);
        ledger.post(new LedgerTransaction(
                source,
                "credit_grant",
                source,
                List.of(
                        LedgerEntry.debit(Account.PROMOTIONS, null, Paise.of(paise)),
                        LedgerEntry.creditLot(Account.RENTER_WALLET, renter, Paise.of(paise), lot.id()))));
        return lot;
    }

    /** Bought credit, as a captured payment posts it. */
    private CreditLot bought(UUID renter, long paise) {
        var source = UUID.randomUUID();
        var lot = ledger.openLot(renter, CreditLot.Kind.PURCHASED, "topup", source, Paise.of(paise), null);
        ledger.post(new LedgerTransaction(
                source,
                "topup",
                source,
                List.of(
                        LedgerEntry.debit(Account.GATEWAY_CLEARING, null, Paise.of(paise)),
                        LedgerEntry.creditLot(Account.RENTER_PURCHASED, renter, Paise.of(paise), lot.id()))));
        return lot;
    }

    private long remaining(CreditLot lot) {
        return jdbc.queryForObject(
                "select remaining_paise from billing.credit_lots where id = ?", Long.class, lot.id());
    }

    /** A session's charge of {@code paise} to {@code renter}, settled as metering settles it. */
    private void charge(UUID renter, UUID session, long paise) {
        tx().executeWithoutResult(status -> {
            assertThat(reservations.reserve(renter, session, Paise.of(paise))).isTrue();
            charges.settle(new SessionCharges.Charge(
                    session,
                    renter,
                    UUID.randomUUID(),
                    List.of(new SessionCharges.Part(UUID.randomUUID(), Paise.of(paise))),
                    Paise.ZERO));
            reservations.release(session);
        });
    }

    @Test
    void freeCreditIsSpentFirstTheSoonestToExpireFirstThenBoughtCreditTheOldestFirst() {
        var renter = UUID.randomUUID();
        var now = Instant.now();
        var older = bought(renter, 300);
        var never = free(renter, 100, null);
        var later = free(renter, 100, now.plus(Duration.ofDays(60)));
        var soon = free(renter, 100, now.plus(Duration.ofDays(5)));
        var newer = bought(renter, 300);

        var session = UUID.randomUUID();
        charge(renter, session, 650);

        assertThat(remaining(soon)).isZero();
        assertThat(remaining(later)).isZero();
        assertThat(remaining(never)).isZero();
        assertThat(remaining(older)).isZero();
        assertThat(remaining(newer)).isEqualTo(250);
        // The charge says which lots it drew from: one entry a lot.
        assertThat(jdbc.queryForObject("""
                        select count(distinct lot_id) from billing.ledger_entries
                        where ref_id = ? and ref_type = 'session' and account in ('renter_wallet', 'renter_purchased')
                        """, Integer.class, session)).isEqualTo(5);
    }

    @Test
    void freeCreditExpiresButNeverWhatAHoldNeeds() {
        var renter = UUID.randomUUID();
        var lot = free(renter, 1_000, Instant.now().minus(Duration.ofMinutes(1)));
        var session = UUID.randomUUID();
        tx().executeWithoutResult(status -> reservations.reserve(renter, session, Paise.of(400)));

        expiry.expire(Instant.now());
        assertThat(remaining(lot)).isEqualTo(400);
        // Running it again the same day changes nothing.
        expiry.expire(Instant.now());
        assertThat(remaining(lot)).isEqualTo(400);

        tx().executeWithoutResult(status -> reservations.release(session));
        expiry.expire(Instant.now());
        assertThat(remaining(lot)).isZero();
        assertThat(jdbc.queryForObject("""
                        select coalesce(sum(amount_paise), 0) from billing.ledger_entries
                        where account = 'free_credit_expired' and ref_id = ?
                        """, Long.class, lot.id())).isEqualTo(1_000);
    }

    @Test
    void boughtCreditAndGiveBacksNeverExpire() {
        var renter = UUID.randomUUID();
        var lot = bought(renter, 500);

        expiry.expire(Instant.now().plus(Duration.ofDays(3_650)));

        assertThat(remaining(lot)).isEqualTo(500);
    }

    @Test
    void lostTimeGoesBackToTheLotsItCameFromAndAnExpiredOnesPartToANewFreeLot() {
        var renter = UUID.randomUUID();
        var expiring = free(renter, 100, Instant.now().plus(Duration.ofSeconds(2)));
        var paid = bought(renter, 400);
        var session = UUID.randomUUID();
        charge(renter, session, 300);
        assertThat(remaining(expiring)).isZero();
        assertThat(remaining(paid)).isEqualTo(200);

        jdbc.update(
                "update billing.credit_lots set expires_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                expiring.id());
        tx().executeWithoutResult(status -> charges.refund(
                session,
                renter,
                UUID.randomUUID(),
                List.of(new SessionCharges.RefundPart(UUID.randomUUID(), Paise.of(200), Paise.of(50)))));

        // 250 back: 200 to the bought lot (spent last, so given back first), 50 to a new free lot, since the free lot
        // it came from has expired.
        assertThat(remaining(paid)).isEqualTo(400);
        assertThat(remaining(expiring)).isZero();
        assertThat(jdbc.queryForObject("""
                        select remaining_paise from billing.credit_lots
                        where user_id = ? and source_type = 'session_refund' and kind = 'free'
                        """, Long.class, renter)).isEqualTo(50);
        assertThat(reconciliation.run("test").mismatches()).isEmpty();
    }

    @Test
    void theNightlyCheckFindsALotThatDisagreesWithItsEntries() {
        var renter = UUID.randomUUID();
        var lot = free(renter, 700, null);
        assertThat(reconciliation.run("test").mismatches()).isEmpty();

        jdbc.update("update billing.credit_lots set remaining_paise = 600 where id = ?", lot.id());
        try {
            assertThat(reconciliation.run("test").mismatches()).anySatisfy(mismatch -> {
                assertThat(mismatch.kind()).isEqualTo(WalletReconciliation.Kind.LOT);
                assertThat(mismatch.userId()).isEqualTo(renter);
                assertThat(mismatch.walletPaise()).isEqualTo(600);
                assertThat(mismatch.expectedPaise()).isEqualTo(700);
            });
        } finally {
            jdbc.update("update billing.credit_lots set remaining_paise = 700 where id = ?", lot.id());
        }
    }
}
