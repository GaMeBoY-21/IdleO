package com.idleo.controlplane.billing.internal;

import static com.idleo.controlplane.billing.internal.LedgerEntry.credit;
import static com.idleo.controlplane.billing.internal.LedgerEntry.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.AuditTrail;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Free credit grants and reversals (ADR 0014): audited, re-runnable, limited. */
@ControlPlaneTest
class CreditGrantsTests {

    private static final AuditTrail.Actor OPERATOR = AuditTrail.Actor.operator("test-operator");

    @Autowired
    CreditGrants grants;

    @Autowired
    Ledger ledger;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private UUID user() throws Exception {
        return TestUsers.signUp(mvc).id();
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    private long balance(UUID user) {
        return jdbc.queryForObject("select balance_paise from billing.wallets where user_id = ?", Long.class, user);
    }

    private int grantRows(UUID user) {
        return jdbc.queryForObject("select count(*) from billing.credit_grants where user_id = ?", Integer.class, user);
    }

    @Test
    void aGrantCreditsTheWalletAndRecordsWhoAndWhy() throws Exception {
        var user = user();

        var outcome = grants.grant(user, Paise.of(50_000), "Welcome credit", key(), OPERATOR);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.walletBalance()).isEqualTo(Paise.of(50_000));
        assertThat(balance(user)).isEqualTo(50_000);
        assertThat(jdbc.queryForMap(
                        "select reason, granted_by, amount_paise from billing.credit_grants where user_id = ?", user))
                .containsEntry("reason", "Welcome credit")
                .containsEntry("granted_by", OPERATOR.label())
                .containsEntry("amount_paise", 50_000L);
        assertThat(jdbc.queryForList(
                        "select account from billing.ledger_entries where txn_id = ? order by direction desc",
                        String.class,
                        outcome.grant().txnId()))
                .containsExactly("promotions", "renter_wallet");
    }

    @Test
    void theSameKeyTwiceGrantsOnce() throws Exception {
        var user = user();
        var key = key();
        var first = grants.grant(user, Paise.of(10_000), "Welcome credit", key, OPERATOR);

        var second = grants.grant(user, Paise.of(10_000), "Welcome credit", key, OPERATOR);

        assertThat(second.created()).isFalse();
        assertThat(second.grant()).isEqualTo(first.grant());
        assertThat(grantRows(user)).isEqualTo(1);
        assertThat(balance(user)).isEqualTo(10_000);
    }

    @Test
    void theSameKeyForADifferentAmountOrUserIsRefused() throws Exception {
        var user = user();
        var key = key();
        grants.grant(user, Paise.of(10_000), "Welcome credit", key, OPERATOR);

        assertThatThrownBy(() -> grants.grant(user, Paise.of(20_000), "Welcome credit", key, OPERATOR))
                .isInstanceOf(CreditGrants.Refused.class)
                .hasMessageContaining("already used");
        var other = user();
        assertThatThrownBy(() -> grants.grant(other, Paise.of(10_000), "Welcome credit", key, OPERATOR))
                .isInstanceOf(CreditGrants.Refused.class);
        assertThat(balance(user)).isEqualTo(10_000);
        assertThat(grantRows(other)).isZero();
    }

    @Test
    void concurrentGrantsWithDifferentKeysAllLand() throws Exception {
        var user = user();
        int count = 20;
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<CreditGrants.GrantOutcome>>();
        try (var pool = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return grants.grant(user, Paise.of(1_000), "Concurrency test", key(), OPERATOR);
                }));
            }
            start.countDown();
            for (var result : results) {
                assertThat(result.get().created()).isTrue();
            }
        }

        assertThat(balance(user)).isEqualTo(20_000);
        assertThat(grantRows(user)).isEqualTo(count);
        assertThat(jdbc.queryForObject("""
                        select sum(case direction when 'credit' then amount_paise else -amount_paise end)
                        from billing.ledger_entries where account = 'renter_wallet' and owner_id = ?
                        """, Long.class, user)).isEqualTo(20_000L);
    }

    @Test
    void aGrantOverTheLimitOrTheWalletCapIsRefused() throws Exception {
        var user = user();

        assertThatThrownBy(() -> grants.grant(user, Paise.of(500_001), "Too much", key(), OPERATOR))
                .isInstanceOf(CreditGrants.Refused.class)
                .hasMessageContaining("at most 500000 paise");

        for (int i = 0; i < 4; i++) {
            grants.grant(user, Paise.of(500_000), "Up to the cap", key(), OPERATOR);
        }
        assertThat(balance(user)).isEqualTo(2_000_000);
        assertThatThrownBy(() -> grants.grant(user, Paise.of(1), "Over the cap", key(), OPERATOR))
                .isInstanceOf(CreditGrants.Refused.class)
                .hasMessageContaining("above the cap");
        assertThat(balance(user)).isEqualTo(2_000_000);
        assertThat(grantRows(user)).isEqualTo(4);
    }

    @Test
    void anUnknownUserOrAMissingReasonIsRefused() throws Exception {
        assertThatThrownBy(() -> grants.grant(UUID.randomUUID(), Paise.of(100), "Welcome credit", key(), OPERATOR))
                .isInstanceOf(CreditGrants.Refused.class)
                .hasMessageContaining("no user");
        var user = user();
        assertThatThrownBy(() -> grants.grant(user, Paise.of(100), " ", key(), OPERATOR))
                .isInstanceOf(CreditGrants.Refused.class);
        assertThatThrownBy(() -> grants.grant(user, Paise.ZERO, "Nothing", key(), OPERATOR))
                .isInstanceOf(CreditGrants.Refused.class);
    }

    @Test
    void aReversalWorksOnceAndIsSafeToRepeat() throws Exception {
        var user = user();
        var grant = grants.grant(user, Paise.of(50_000), "Welcome credit", key(), OPERATOR)
                .grant();

        var first = grants.reverse(grant.id(), "Granted to the wrong account", OPERATOR);
        var second = grants.reverse(grant.id(), "Granted to the wrong account", OPERATOR);

        assertThat(first.created()).isTrue();
        assertThat(first.walletBalance()).isEqualTo(Paise.ZERO);
        assertThat(second.created()).isFalse();
        assertThat(second.reversal()).isEqualTo(first.reversal());
        assertThat(balance(user)).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.credit_grant_reversals where grant_id = ?",
                        Integer.class,
                        grant.id()))
                .isEqualTo(1);
        assertThatThrownBy(() -> grants.reverse(UUID.randomUUID(), "No such grant", OPERATOR))
                .isInstanceOf(CreditGrants.Refused.class);
    }

    @Test
    void creditAlreadySpentCantBeReversed() throws Exception {
        var user = user();
        var grant = grants.grant(user, Paise.of(1_000), "Welcome credit", key(), OPERATOR)
                .grant();
        // A settlement spends some of it, as one will once sessions exist.
        ledger.post(new LedgerTransaction(
                UUID.randomUUID(),
                "session",
                UUID.randomUUID(),
                List.of(
                        debit(Account.RENTER_WALLET, user, Paise.of(400)),
                        credit(Account.HOST_PAYABLE, UUID.randomUUID(), Paise.of(320)),
                        credit(Account.PLATFORM_REVENUE, null, Paise.of(80)))));

        assertThatThrownBy(() -> grants.reverse(grant.id(), "Too late", OPERATOR))
                .isInstanceOf(CreditGrants.Refused.class)
                .hasMessageContaining("600 paise available");
        assertThat(balance(user)).isEqualTo(600);
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.credit_grant_reversals where grant_id = ?",
                        Integer.class,
                        grant.id()))
                .isZero();
    }
}
