package com.idleo.controlplane.billing.internal;

import static com.idleo.controlplane.billing.internal.LedgerEntry.credit;
import static com.idleo.controlplane.billing.internal.LedgerEntry.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The posting primitive (ADR 0014): balanced, idempotent, and the wallet cache moves with the ledger. */
@ControlPlaneTest
class LedgerTests {

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcTemplate jdbc;

    /** A grant of free credit into a lot opened for it (ADR 0039), named by the transaction. */
    private LedgerTransaction grant(UUID txnId, UUID renter, long paise) {
        var lot = ledger.openLot(renter, CreditLot.Kind.FREE, "credit_grant", txnId, Paise.of(paise), null);
        return new LedgerTransaction(
                txnId,
                "credit_grant",
                txnId,
                List.of(
                        debit(Account.PROMOTIONS, null, Paise.of(paise)),
                        LedgerEntry.creditLot(Account.RENTER_WALLET, renter, Paise.of(paise), lot.id())));
    }

    private long balance(UUID renter) {
        return jdbc.queryForObject("select balance_paise from billing.wallets where user_id = ?", Long.class, renter);
    }

    private int entries(UUID txnId) {
        return jdbc.queryForObject(
                "select count(*) from billing.ledger_entries where txn_id = ?", Integer.class, txnId);
    }

    @Test
    void aPostingWritesTheEntriesAndCreatesTheWallet() {
        var renter = UUID.randomUUID();
        var txn = UUID.randomUUID();

        var posting = ledger.post(grant(txn, renter, 50_000));

        assertThat(posting.posted()).isTrue();
        assertThat(entries(txn)).isEqualTo(2);
        assertThat(balance(renter)).isEqualTo(50_000);
    }

    @Test
    void postingTheSameTransactionTwiceChangesNothingTheSecondTime() {
        var renter = UUID.randomUUID();
        var transaction = grant(UUID.randomUUID(), renter, 10_000);
        var first = ledger.post(transaction);

        var second = ledger.post(transaction);

        assertThat(second.posted()).isFalse();
        assertThat(second.postedAt()).isEqualTo(first.postedAt());
        assertThat(entries(transaction.txnId())).isEqualTo(2);
        assertThat(balance(renter)).isEqualTo(10_000);
    }

    @Test
    void aTransactionIdReusedForDifferentEntriesIsRefused() {
        var renter = UUID.randomUUID();
        var txn = UUID.randomUUID();
        var first = grant(txn, renter, 10_000);
        ledger.post(first);
        var different = new LedgerTransaction(
                txn,
                "credit_grant",
                txn,
                List.of(
                        debit(Account.PROMOTIONS, null, Paise.of(5_000)),
                        LedgerEntry.creditLot(
                                Account.RENTER_WALLET,
                                renter,
                                Paise.of(5_000),
                                first.entries().get(1).lotId())));

        assertThatThrownBy(() -> ledger.post(different)).isInstanceOf(IllegalStateException.class);
        assertThat(balance(renter)).isEqualTo(10_000);
    }

    @Test
    void spendingLowersTheWalletAndAPostingThatWouldGoNegativeFailsLoudly() {
        var renter = UUID.randomUUID();
        var host = UUID.randomUUID();
        ledger.post(grant(UUID.randomUUID(), renter, 1_200));
        var settlement = new LedgerTransaction(
                UUID.randomUUID(),
                "session",
                UUID.randomUUID(),
                List.of(
                        ledger.draw(renter, Paise.of(1_200)).getFirst(),
                        credit(Account.HOST_PAYABLE, host, Paise.of(960)),
                        credit(Account.PLATFORM_REVENUE, null, Paise.of(240))));

        ledger.post(settlement);
        assertThat(balance(renter)).isZero();

        var tooMuch = new LedgerTransaction(
                UUID.randomUUID(),
                "session",
                UUID.randomUUID(),
                List.of(
                        LedgerEntry.debitLot(
                                Account.RENTER_WALLET,
                                renter,
                                Paise.of(1),
                                settlement.entries().getFirst().lotId()),
                        credit(Account.PLATFORM_REVENUE, null, Paise.of(1))));
        assertThatThrownBy(() -> ledger.post(tooMuch)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.draw(renter, Paise.of(1))).isInstanceOf(IllegalStateException.class);
        assertThat(entries(tooMuch.txnId())).isZero();
    }

    @Test
    void aRenterWalletEntryWithoutAnOwnerIsAValidationError() {
        var transaction = new LedgerTransaction(
                UUID.randomUUID(),
                "credit_grant",
                UUID.randomUUID(),
                List.of(
                        debit(Account.PROMOTIONS, null, Paise.of(100)),
                        credit(Account.RENTER_WALLET, null, Paise.of(100))));

        assertThatThrownBy(() -> ledger.post(transaction))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("renter_wallet needs an owner");
        assertThat(entries(transaction.txnId())).isZero();
    }

    @Test
    void theCodeChecksATransactionBeforeTheDatabaseDoes() {
        var renter = UUID.randomUUID();
        var refused = List.of(
                // One entry.
                List.of(credit(Account.RENTER_WALLET, renter, Paise.of(100))),
                // Unbalanced.
                List.of(
                        debit(Account.PROMOTIONS, null, Paise.of(100)),
                        credit(Account.RENTER_WALLET, renter, Paise.of(99))),
                // A renter wallet without its owner, a platform account with one.
                List.of(
                        debit(Account.PROMOTIONS, null, Paise.of(100)),
                        credit(Account.RENTER_WALLET, null, Paise.of(100))),
                List.of(
                        debit(Account.PROMOTIONS, renter, Paise.of(100)),
                        credit(Account.RENTER_WALLET, renter, Paise.of(100))),
                // Zero.
                List.of(
                        debit(Account.PROMOTIONS, null, Paise.ZERO),
                        credit(Account.RENTER_WALLET, renter, Paise.ZERO)));
        for (var entries : refused) {
            var transaction = new LedgerTransaction(UUID.randomUUID(), "credit_grant", UUID.randomUUID(), entries);
            assertThatThrownBy(() -> ledger.post(transaction)).isInstanceOf(IllegalArgumentException.class);
            assertThat(entries(transaction.txnId())).isZero();
        }
    }
}
