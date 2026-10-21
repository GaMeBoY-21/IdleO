package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.OperatorCommand;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.AuditTrail;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Billing's admin actions (ADR 0027): free credit granted and reversed, the ledger looked up, reconciliations read. */
@ControlPlaneTest
class AdminMoneyTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CreditGrants grants;

    @Autowired
    WalletReconciliation reconciliation;

    @Autowired
    TransactionTemplate transactions;

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder grantRequest(UUID userId, long paise, String key) {
        var request = post("/admin/v1/credit-grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"paise\":" + paise + ",\"reason\":\"pilot welcome credit\"}");
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private int audited(String action, String target) {
        return jdbc.queryForObject(
                "select count(*) from identity.audit_log where action = ? and target_id = ?",
                Integer.class,
                action,
                target);
    }

    @Test
    void grantsAndReversesCreditOnceEachAuditedAndShownInTheAuditLog() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var renter = TestUsers.signUp(mvc);
        var key = UUID.randomUUID().toString();

        mvc.perform(admin.as(grantRequest(renter.id(), 50_000, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/idempotency-key-invalid"));
        var granted = body(
                mvc.perform(admin.as(grantRequest(renter.id(), 50_000, key))).andExpect(status().isCreated()));
        assertThat(granted.get("amountPaise").asLong()).isEqualTo(50_000);
        assertThat(granted.get("walletBalancePaise").asLong()).isEqualTo(50_000);
        assertThat(granted.get("grantedBy").asString()).isEqualTo("admin " + admin.id());
        mvc.perform(admin.as(grantRequest(renter.id(), 50_000, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(granted.get("id").asString()));
        assertThat(audited("credit.grant", renter.id().toString())).isOne();
        mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.balancePaise").value(50_000));

        // In the audit log, as the console shows it.
        var log = body(mvc.perform(admin.as(get("/admin/v1/audit")
                                .param("action", "credit.")
                                .param("targetId", renter.id().toString())))
                        .andExpect(status().isOk()))
                .get("entries");
        assertThat(log).hasSize(1);
        assertThat(log.get(0).get("actorUserId").asString())
                .isEqualTo(admin.id().toString());
        assertThat(log.get(0).get("after").get("amountPaise").asLong()).isEqualTo(50_000);
        assertThat(log.get(0).get("reason").asString()).isEqualTo("pilot welcome credit");

        // And in the ledger, by the renter's wallet and by its transaction.
        var wallet = body(mvc.perform(admin.as(get("/admin/v1/ledger")
                        .param("account", "renter_wallet")
                        .param("owner", renter.id().toString()))))
                .get("entries");
        assertThat(wallet).hasSize(1);
        assertThat(wallet.get(0).get("refType").asString()).isEqualTo("credit_grant");
        assertThat(body(mvc.perform(admin.as(get("/admin/v1/ledger")
                                .param("txn", granted.get("txnId").asString()))))
                        .get("entries"))
                .hasSize(2);

        var path = "/admin/v1/credit-grants/" + granted.get("id").asString() + "/reverse";
        var reason = "{\"reason\":\"granted to the wrong account\"}";
        var reversed = body(mvc.perform(admin.as(post(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reason))
                .andExpect(status().isOk()));
        assertThat(reversed.get("reversalId").isNull()).isFalse();
        assertThat(reversed.get("walletBalancePaise").asLong()).isZero();
        mvc.perform(admin.as(post(path)).contentType(MediaType.APPLICATION_JSON).content(reason))
                .andExpect(status().isOk());
        assertThat(audited("credit.reverse", granted.get("id").asString())).isOne();
        assertThat(body(mvc.perform(admin.as(get("/admin/v1/credit-grants")
                                .param("user", renter.id().toString()))))
                        .get("grants")
                        .get(0)
                        .get("reversedBy")
                        .asString())
                .startsWith("admin ");
    }

    @Test
    void aRefusedGrantChangesNothingAndIsntAudited() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var renter = TestUsers.signUp(mvc);
        mvc.perform(admin.as(
                        grantRequest(renter.id(), 500_001, UUID.randomUUID().toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/action-refused"));
        mvc.perform(admin.as(grantRequest(renter.id(), 1, UUID.randomUUID().toString())))
                .andExpect(status().isCreated());
        mvc.perform(admin.as(
                        grantRequest(UUID.randomUUID(), 1_000, UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        assertThat(audited("credit.grant", renter.id().toString())).isOne();
    }

    @Test
    void aGrantItsLedgerPostingAndItsAuditRowRollBackTogether() throws Exception {
        var renter = TestUsers.signUp(mvc);
        transactions.executeWithoutResult(status -> {
            grants.grant(
                    renter.id(),
                    Paise.of(1_000),
                    "rolled back",
                    "rollback-" + UUID.randomUUID(),
                    AuditTrail.Actor.operator("tester"));
            status.setRollbackOnly();
        });
        assertThat(audited("credit.grant", renter.id().toString())).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.credit_grants where user_id = ?", Integer.class, renter.id()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.ledger_entries where owner_id = ?", Integer.class, renter.id()))
                .isZero();
    }

    @Test
    void grantCreditsStillWorksThroughTheSameService() throws Exception {
        var renter = TestUsers.signUp(mvc);
        var out = new ByteArrayOutputStream();
        new CreditCommands()
                .grantCredits(grants)
                .run(
                        OperatorCommand.Arguments.parse(
                                "grant-credits",
                                "--user",
                                renter.id().toString(),
                                "--paise",
                                "2500",
                                "--reason",
                                "command check",
                                "--key",
                                "command-" + UUID.randomUUID()),
                        "nikki",
                        new PrintStream(out, true, StandardCharsets.UTF_8));
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("granted 2500 paise");
        assertThat(jdbc.queryForObject(
                        "select actor_kind || ':' || actor_name from identity.audit_log"
                                + " where action = 'credit.grant' and target_id = ?",
                        String.class,
                        renter.id().toString()))
                .isEqualTo("operator:nikki");
    }

    @Test
    void reverseGrantAndReconcileWalletsStillWorkThroughTheSameServices() throws Exception {
        var renter = TestUsers.signUp(mvc);
        var grant = grants.grant(
                        renter.id(),
                        Paise.of(700),
                        "to be reversed",
                        "command-" + UUID.randomUUID(),
                        AuditTrail.Actor.operator("nikki"))
                .grant();
        var commands = new CreditCommands();
        var out = new ByteArrayOutputStream();
        commands.reverseGrant(grants)
                .run(
                        OperatorCommand.Arguments.parse(
                                "reverse-grant", "--grant", grant.id().toString(), "--reason", "wrong account"),
                        "nikki",
                        new PrintStream(out, true, StandardCharsets.UTF_8));
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("reversed grant " + grant.id());
        assertThat(audited("credit.reverse", grant.id().toString())).isOne();

        var checked = new ByteArrayOutputStream();
        new ReconciliationCommand()
                .reconcileWallets(reconciliation)
                .run(
                        OperatorCommand.Arguments.parse("reconcile-wallets"),
                        "nikki",
                        new PrintStream(checked, true, StandardCharsets.UTF_8));
        assertThat(checked.toString(StandardCharsets.UTF_8)).contains("0 mismatches");
        var runId = jdbc.queryForObject(
                "select id from billing.reconciliation_runs where trigger = 'nikki' order by finished_at desc limit 1",
                UUID.class);
        assertThat(audited("reconciliation.run", runId.toString())).isOne();
    }

    @Test
    void showsTheLatestReconciliationRunsWithWhatTheyFound() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        assertThat(reconciliation.run("tests").ran()).isTrue();
        var runs = body(mvc.perform(admin.as(get("/admin/v1/reconciliation").param("limit", "1")))
                        .andExpect(status().isOk()))
                .get("runs");
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("trigger").asString()).isEqualTo("tests");
        assertThat(runs.get(0).get("ledgerBalanced").asBoolean()).isTrue();
        assertThat(runs.get(0).get("mismatchCount").asInt())
                .isEqualTo(runs.get(0).get("mismatches").size());

        // Run now from the console, as reconcile-wallets does: kept, and audited with the run it made.
        var run = body(mvc.perform(admin.as(post("/admin/v1/reconciliation"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"checking after a manual grant\"}"))
                .andExpect(status().isOk()));
        assertThat(run.get("trigger").asString()).isEqualTo("admin " + admin.id());
        assertThat(audited("reconciliation.run", run.get("id").asString())).isOne();
        mvc.perform(admin.as(get("/admin/v1/ledger"))).andExpect(status().isBadRequest());
        mvc.perform(admin.as(get("/admin/v1/ledger").param("account", "nonsense")))
                .andExpect(status().isBadRequest());
    }
}
