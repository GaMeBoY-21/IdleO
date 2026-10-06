package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database's own guards on the money (ADR 0014), hit with raw SQL so no Java check is involved:
 * whatever slips past the code, the database still refuses.
 */
@ControlPlaneTest
class LedgerDatabaseGuardTests {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private void insertEntry(UUID txnId, String account, UUID ownerId, long amount, String direction) {
        jdbc.update(
                """
                insert into billing.ledger_entries
                    (id, txn_id, account, owner_id, amount_paise, direction, ref_type, ref_id, created_at)
                values (?, ?, ?, ?, ?, ?, 'test', ?, ?)
                """,
                UUID.randomUUID(),
                txnId,
                account,
                ownerId,
                amount,
                direction,
                UUID.randomUUID(),
                Timestamp.from(Instant.now()));
    }

    /** Platform accounts only: raw SQL must never leave a renter's wallet cache out of step with the ledger. */
    private UUID balancedTransaction() {
        var txn = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertEntry(txn, "promotions", null, 500, "debit");
            insertEntry(txn, "platform_revenue", null, 500, "credit");
        });
        return txn;
    }

    private int entries(UUID txnId) {
        return jdbc.queryForObject(
                "select count(*) from billing.ledger_entries where txn_id = ?", Integer.class, txnId);
    }

    @Test
    void anUnbalancedTransactionFailsAtCommitNotAtTheInsert() {
        var txn = UUID.randomUUID();
        var inserted = new AtomicBoolean();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    insertEntry(txn, "promotions", null, 500, "debit");
                    insertEntry(txn, "renter_wallet", UUID.randomUUID(), 499, "credit");
                    inserted.set(true);
                }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("unbalanced");

        assertThat(inserted).as("both inserts succeeded; the commit failed").isTrue();
        assertThat(entries(txn)).isZero();
    }

    @Test
    void aBalancedTransactionCommits() {
        assertThat(entries(balancedTransaction())).isEqualTo(2);
    }

    @Test
    void entriesCanNeverBeChangedDeletedOrTruncated() {
        var txn = balancedTransaction();

        assertThatThrownBy(
                        () -> jdbc.update("update billing.ledger_entries set amount_paise = 1 where txn_id = ?", txn))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from billing.ledger_entries where txn_id = ?", txn))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate billing.ledger_entries"))
                .hasMessageContaining("append-only");
        assertThat(entries(txn)).isEqualTo(2);
    }

    @Test
    void aRenterOrHostEntryNeedsAnOwnerAndAPlatformEntryCantHaveOne() {
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "renter_wallet", null, 100, "credit"))
                .hasMessageContaining("ledger_entries_owner");
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "host_payable", null, 100, "credit"))
                .hasMessageContaining("ledger_entries_owner");
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "platform_revenue", UUID.randomUUID(), 100, "credit"))
                .hasMessageContaining("ledger_entries_owner");
    }

    @Test
    void amountsAndAccountsAreChecked() {
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "promotions", null, 0, "debit"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "petty_cash", null, 100, "debit"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "promotions", null, 100, "sideways"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theSameTransactionAccountAndOwnerCanOnlyBePostedOnce() {
        var txn = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    insertEntry(txn, "promotions", null, 500, "debit");
                    insertEntry(txn, "promotions", null, 500, "credit");
                }))
                .hasMessageContaining("ledger_entries_txn_account_owner_key");
    }

    @Test
    void aWalletCanNeverGoNegativeOrReserveMoreThanItHolds() {
        var user = UUID.randomUUID();
        jdbc.update("insert into billing.wallets values (?, 1000, 0, 0, ?)", user, Timestamp.from(Instant.now()));

        try {
            assertThatThrownBy(
                            () -> jdbc.update("update billing.wallets set balance_paise = -1 where user_id = ?", user))
                    .hasMessageContaining("wallets_never_negative");
            assertThatThrownBy(() ->
                            jdbc.update("update billing.wallets set reserved_paise = 1001 where user_id = ?", user))
                    .hasMessageContaining("wallets_never_negative");
            assertThatThrownBy(
                            () -> jdbc.update("update billing.wallets set reserved_paise = -1 where user_id = ?", user))
                    .hasMessageContaining("wallets_never_negative");
        } finally {
            // A wallet with no ledger behind it would be a mismatch for the nightly check.
            jdbc.update("delete from billing.wallets where user_id = ?", user);
        }
    }

    @Test
    void creditGrantRecordsAreAppendOnly() {
        var grant = UUID.randomUUID();
        jdbc.update(
                """
                insert into billing.credit_grants values (?, ?, 100, 'test', 'test', ?, ?, ?)
                """, grant, UUID.randomUUID(), "guard-test-" + grant, UUID.randomUUID(), Timestamp.from(Instant.now()));

        assertThatThrownBy(() -> jdbc.update("update billing.credit_grants set amount_paise = 1 where id = ?", grant))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from billing.credit_grants where id = ?", grant))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate billing.credit_grant_reversals, billing.credit_grants"))
                .hasMessageContaining("append-only");
    }
}
