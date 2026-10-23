package com.idleo.controlplane.billing.internal;

import static com.idleo.controlplane.billing.internal.LedgerEntry.debit;
import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.AuditTrail;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Billing's meters say what the ledger says (ADR 0036): once per posting, and never for one that rolled back. */
@ControlPlaneTest
class BillingMetricsTests {

    private static final AuditTrail.Actor OPERATOR = AuditTrail.Actor.operator("test-operator");

    @Autowired
    CreditGrants grants;

    @Autowired
    Ledger ledger;

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    PlatformTransactionManager transactions;

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double grants() {
        return count("billing.ledger.transactions", "ref_type", CreditGrants.GRANT_REF);
    }

    private double granted() {
        return count(
                "billing.ledger.paise",
                "ref_type",
                CreditGrants.GRANT_REF,
                "account",
                "renter_wallet",
                "direction",
                "credit");
    }

    @Test
    void aGrantCountsOnceWithItsPaise() throws Exception {
        var user = TestUsers.signUp(mvc).id();
        var grantsBefore = grants();
        var grantedBefore = granted();
        var key = "test-" + UUID.randomUUID();

        grants.grant(user, Paise.of(12_345), "Welcome credit", key, OPERATOR);
        grants.grant(user, Paise.of(12_345), "Welcome credit", key, OPERATOR);

        assertThat(grants() - grantsBefore)
                .as("the same grant again posts nothing")
                .isEqualTo(1);
        assertThat(granted() - grantedBefore).isEqualTo(12_345);
        assertThat(meters.get("billing.reserved.paise").gauge().value()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aPostingThatRollsBackIsNeverCounted() throws Exception {
        var user = TestUsers.signUp(mvc).id();
        var before = count("billing.ledger.transactions", "ref_type", "metrics_test");

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ledger.post(new LedgerTransaction(
                    UUID.randomUUID(),
                    "metrics_test",
                    UUID.randomUUID(),
                    List.of(
                            debit(Account.PROMOTIONS, null, Paise.of(100)),
                            LedgerEntry.creditLot(
                                    Account.RENTER_WALLET,
                                    user,
                                    Paise.of(100),
                                    ledger.openLot(
                                                    user,
                                                    CreditLot.Kind.FREE,
                                                    "metrics_test",
                                                    UUID.randomUUID(),
                                                    Paise.of(100),
                                                    null)
                                            .id()))));
            status.setRollbackOnly();
        });

        assertThat(count("billing.ledger.transactions", "ref_type", "metrics_test") - before)
                .isZero();
    }
}
