package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.identity.AuditTrail;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** What a renter sees of their own wallet (ADR 0014). */
@ControlPlaneTest
class WalletApiTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    CreditGrants grants;

    @Autowired
    JdbcTemplate jdbc;

    private String body(TestUser user, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private CreditGrants.Grant grant(TestUser user, long paise) {
        return grants.grant(
                        user.id(),
                        Paise.of(paise),
                        "Welcome credit",
                        "test-" + UUID.randomUUID(),
                        AuditTrail.Actor.operator("test-operator"))
                .grant();
    }

    @Test
    void afterAFiveHundredRupeeGrantTheRenterSeesItAndThenItsReversal() throws Exception {
        var renter = TestUsers.signUp(mvc);
        var grant = grant(renter, 50_000);

        mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balancePaise").value(50_000))
                .andExpect(jsonPath("$.reservedPaise").value(0))
                .andExpect(jsonPath("$.availablePaise").value(50_000));
        mvc.perform(get("/v1/wallet/transactions").header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].kind").value("free_credit"))
                .andExpect(jsonPath("$.entries[0].amountPaise").value(50_000))
                .andExpect(jsonPath("$.entries[0].direction").value("credit"))
                .andExpect(jsonPath("$.entries[0].reference.type").value("credit_grant"))
                .andExpect(
                        jsonPath("$.entries[0].reference.id").value(grant.id().toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        grants.reverse(grant.id(), "Granted to the wrong account", AuditTrail.Actor.operator("test-operator"));

        var wallet = body(renter, "/v1/wallet");
        var transactions = body(renter, "/v1/wallet/transactions");
        System.out.println("GET /v1/wallet after a ₹500 grant and its reversal:\n" + wallet);
        System.out.println("GET /v1/wallet/transactions:\n"
                + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(transactions)));
        assertThat(JSON.readTree(wallet).get("availablePaise").asLong()).isZero();
        var entries = JSON.readTree(transactions).get("entries");
        assertThat(entries.size()).isEqualTo(2);
        assertThat(entries.get(0).get("kind").asString()).isEqualTo("free_credit_reversed");
        assertThat(entries.get(0).get("direction").asString()).isEqualTo("debit");
        assertThat(entries.get(1).get("kind").asString()).isEqualTo("free_credit");
    }

    @Test
    void beforeAnyCreditTheWalletIsZeroAndNothingIsCreated() throws Exception {
        var renter = TestUsers.signUp(mvc);

        mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.balancePaise").value(0))
                .andExpect(jsonPath("$.availablePaise").value(0));
        mvc.perform(get("/v1/wallet/transactions").header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.entries").isEmpty());
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.wallets where user_id = ?", Integer.class, renter.id()))
                .isZero();
    }

    @Test
    void anotherUsersEntriesNeverAppear() throws Exception {
        var alice = TestUsers.signUp(mvc);
        var bob = TestUsers.signUp(mvc);
        grant(alice, 10_000);

        mvc.perform(get("/v1/wallet").header("Authorization", bob.bearer()))
                .andExpect(jsonPath("$.balancePaise").value(0));
        mvc.perform(get("/v1/wallet/transactions").header("Authorization", bob.bearer()))
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void theHistoryPagesNewestFirstWithAnOpaqueCursor() throws Exception {
        var renter = TestUsers.signUp(mvc);
        for (int i = 1; i <= 5; i++) {
            grant(renter, i * 100L);
        }

        var seen = new HashSet<Long>();
        String cursor = null;
        int pages = 0;
        long previous = Long.MAX_VALUE;
        do {
            var page = JSON.readTree(
                    body(renter, "/v1/wallet/transactions?limit=2" + (cursor == null ? "" : "&cursor=" + cursor)));
            for (var entry : page.get("entries")) {
                var amount = entry.get("amountPaise").asLong();
                assertThat(amount).as("newest first").isLessThan(previous);
                previous = amount;
                seen.add(amount);
            }
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyInAnyOrder(100L, 200L, 300L, 400L, 500L);
    }

    @Test
    void aBadLimitOrCursorIsABadRequest() throws Exception {
        var renter = TestUsers.signUp(mvc);
        for (var query : new String[] {"limit=0", "limit=51", "cursor=not-a-cursor"}) {
            mvc.perform(get("/v1/wallet/transactions?" + query).header("Authorization", renter.bearer()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/v1/wallet")).andExpect(status().isUnauthorized());
    }
}
