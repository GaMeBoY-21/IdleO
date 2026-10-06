package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.CreditReservations;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Credit held for booked sessions (ADR 0015): exact, inside the caller's transaction, checkable. */
@ControlPlaneTest
class CreditReservationsTests {

    @Autowired
    CreditReservations reservations;

    @Autowired
    CreditGrants grants;

    @Autowired
    WalletReconciliation reconciliation;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private UUID userWith(long paise) throws Exception {
        var user = TestUsers.signUp(mvc).id();
        grants.grant(user, Paise.of(paise), "Reservation test", "test-" + UUID.randomUUID(), "test-operator");
        return user;
    }

    private long reserved(UUID user) {
        return jdbc.queryForObject("select reserved_paise from billing.wallets where user_id = ?", Long.class, user);
    }

    @Test
    void aHoldLowersTheAvailableBalanceAndAReleaseRestoresItOnce() throws Exception {
        var user = userWith(1_000);
        var session = UUID.randomUUID();

        assertThat(inTransaction(() -> reservations.reserve(user, session, Paise.of(600))))
                .isTrue();
        assertThat(reserved(user)).isEqualTo(600);
        assertThat(reservations.available(user)).isEqualTo(Paise.of(400));
        assertThat(reservations.held(session)).contains(Paise.of(600));
        assertThat(inTransaction(() -> reservations.reserve(user, session, Paise.of(600))))
                .as("holding again changes nothing")
                .isTrue();
        assertThat(reserved(user)).isEqualTo(600);

        assertThat(inTransaction(() -> reservations.release(session))).isTrue();
        assertThat(inTransaction(() -> reservations.release(session))).isFalse();
        assertThat(reserved(user)).isZero();
        assertThat(reservations.available(user)).isEqualTo(Paise.of(1_000));
        assertThat(reservations.held(session)).isEmpty();
    }

    @Test
    void aHoldAboveTheAvailableBalanceIsRefused() throws Exception {
        var user = userWith(1_000);
        inTransaction(() -> reservations.reserve(user, UUID.randomUUID(), Paise.of(700)));

        assertThat(inTransaction(() -> reservations.reserve(user, UUID.randomUUID(), Paise.of(301))))
                .isFalse();
        assertThat(inTransaction(() -> reservations.reserve(UUID.randomUUID(), UUID.randomUUID(), Paise.of(1))))
                .as("no credit at all")
                .isFalse();
        assertThat(reserved(user)).isEqualTo(700);
    }

    @Test
    void holdsOnlyRunInsideTheCallersTransaction() throws Exception {
        var user = userWith(1_000);

        assertThatThrownBy(() -> reservations.reserve(user, UUID.randomUUID(), Paise.of(100)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> reservations.release(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theDatabaseOnlyLetsAnActiveHoldChangeItsAmountOrBeReleased() throws Exception {
        var user = userWith(1_000);
        var session = UUID.randomUUID();
        inTransaction(() -> reservations.reserve(user, session, Paise.of(100)));

        assertThatThrownBy(() -> jdbc.update(
                        "update billing.reservations set session_id = ? where session_id = ?",
                        UUID.randomUUID(),
                        session))
                .hasMessageContaining("only allows changing an active hold's amount, or releasing it");
        assertThatThrownBy(() -> jdbc.update("delete from billing.reservations where session_id = ?", session))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from billing.reservation_changes where session_id = ?", session))
                .hasMessageContaining("append-only");
        inTransaction(() -> reservations.release(session));
        assertThatThrownBy(() -> jdbc.update(
                        "update billing.reservations set status = 'active', released_at = null where session_id = ?",
                        session))
                .hasMessageContaining("only allows changing an active hold's amount, or releasing it");
        assertThatThrownBy(() ->
                        jdbc.update("update billing.reservations set amount_paise = 1 where session_id = ?", session))
                .as("a released hold never changes")
                .hasMessageContaining("only allows changing an active hold's amount, or releasing it");
    }

    @Test
    void theNightlyCheckCatchesAReservationOutOfStepWithItsHolds() throws Exception {
        var user = userWith(1_000);
        inTransaction(() -> reservations.reserve(user, UUID.randomUUID(), Paise.of(300)));
        assertThat(reconciliation.run().mismatches()).isEmpty();

        jdbc.update("update billing.wallets set reserved_paise = reserved_paise + 1 where user_id = ?", user);
        try {
            assertThat(reconciliation.run().mismatches())
                    .containsExactly(
                            new WalletReconciliation.Mismatch(WalletReconciliation.Kind.RESERVED, user, 301, 300));
        } finally {
            jdbc.update("update billing.wallets set reserved_paise = reserved_paise - 1 where user_id = ?", user);
        }
    }
}
