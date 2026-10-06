package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The nightly check (ADR 0014). The ledger is protected by triggers; the cache isn't, so a test can break it. */
@ControlPlaneTest
class WalletReconciliationTests {

    @Autowired
    WalletReconciliation reconciliation;

    @Autowired
    CreditGrants grants;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    MeterRegistry meters;

    private UUID grantedUser(long paise) throws Exception {
        var user = TestUsers.signUp(mvc).id();
        grants.grant(user, Paise.of(paise), "Reconciliation test", "test-" + UUID.randomUUID(), "test-operator");
        return user;
    }

    private double mismatchCount() {
        return meters.counter("billing.reconciliation.mismatches").count();
    }

    @Test
    void aCleanLedgerHasNoMismatches() throws Exception {
        grantedUser(25_000);
        var before = mismatchCount();

        var result = reconciliation.run();

        assertThat(result.ran()).isTrue();
        assertThat(result.mismatches()).isEmpty();
        assertThat(result.ledgerBalanced()).isTrue();
        assertThat(result.ledgerDebitsPaise()).isPositive();
        assertThat(mismatchCount()).isEqualTo(before);
        assertThat(meters.get("billing.reconciliation.last_success").gauge().value())
                .isPositive();
    }

    @Test
    void aCorruptedWalletIsReportedExactlyOnce() throws Exception {
        var user = grantedUser(25_000);
        var before = mismatchCount();
        jdbc.update("update billing.wallets set balance_paise = balance_paise + 1 where user_id = ?", user);
        try {
            var result = reconciliation.run();

            assertThat(result.mismatches())
                    .containsExactly(
                            new WalletReconciliation.Mismatch(WalletReconciliation.Kind.BALANCE, user, 25_001, 25_000));
            assertThat(result.ledgerBalanced()).isTrue();
            assertThat(mismatchCount()).isEqualTo(before + 1);
        } finally {
            jdbc.update("update billing.wallets set balance_paise = balance_paise - 1 where user_id = ?", user);
        }
        assertThat(reconciliation.run().mismatches()).isEmpty();
    }

    @Test
    void anotherInstanceRunningItMeansThisOneSkips() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("select pg_advisory_lock(?)")) {
            statement.setLong(1, WalletReconciliation.LOCK_KEY);
            statement.execute();
            try {
                assertThat(reconciliation.run().ran()).isFalse();
            } finally {
                try (var unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                    unlock.setLong(1, WalletReconciliation.LOCK_KEY);
                    unlock.execute();
                }
            }
        }
        assertThat(reconciliation.run().ran()).isTrue();
    }
}
