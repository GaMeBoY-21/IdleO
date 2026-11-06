package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Money operations (ADR 0055): the ledger explorer and trial balance, the ledger's checks run in the app, manual
 * adjustments by approval and their reversals, closing a month with the database's own guard, and the reports tied to
 * the ledger.
 */
@ControlPlaneTest
class MoneyOperationsTests {

    /** A month long before any test's clock: closing it can't refuse another test's postings. */
    private static final YearMonth LONG_AGO = YearMonth.of(2019, 3);

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
    MoneyReports reports;

    @Autowired
    TransactionTemplate transactions;

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static final AuditTrail.Actor OPERATOR = AuditTrail.Actor.operator("money-operations-test");

    @Test
    void theChartOfAccountsShowsEachBalanceAtAMomentAndTheTrialBalanceBalances() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var renter = TestUsers.signUp(mvc);
        var before = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        var granted = grants.grant(
                renter.id(),
                Paise.of(12_300),
                "chart of accounts test",
                UUID.randomUUID().toString(),
                OPERATOR);

        var mine = body(mvc.perform(admin.as(get("/admin/v1/ledger/accounts")
                                .param("owner", renter.id().toString())))
                        .andExpect(status().isOk()))
                .get("accounts");
        assertThat(balance(mine, "renter_wallet")).isEqualTo(12_300);
        // Only what a person can own, for an owner.
        for (var account : mine) {
            assertThat(account.get("owned").asBoolean()).isTrue();
        }
        var then = body(mvc.perform(admin.as(get("/admin/v1/ledger/accounts")
                        .param("owner", renter.id().toString())
                        .param("at", before.toString()))))
                .get("accounts");
        assertThat(balance(then, "renter_wallet")).isZero();

        var chart =
                body(mvc.perform(admin.as(get("/admin/v1/ledger/accounts")))).get("accounts");
        assertThat(chart).hasSize(Account.values().length);
        var promotions = find(chart, "promotions");
        assertThat(promotions.get("type").asString()).isEqualTo("expense");
        assertThat(promotions.get("normalSide").asString()).isEqualTo("debit");
        assertThat(promotions.get("description").asString()).isNotBlank();
        assertThat(find(chart, "gateway_fees").get("type").asString()).isEqualTo("expense");
        assertThat(find(chart, "gst_input_credit").get("type").asString()).isEqualTo("asset");

        var trial = body(
                mvc.perform(admin.as(get("/admin/v1/ledger/trial-balance"))).andExpect(status().isOk()));
        assertThat(trial.get("balanced").asBoolean()).isTrue();
        assertThat(trial.get("debitsPaise").asLong())
                .isEqualTo(trial.get("creditsPaise").asLong());

        var transaction = body(mvc.perform(admin.as(
                        get("/admin/v1/ledger/transactions/" + granted.grant().txnId())))
                .andExpect(status().isOk()));
        assertThat(transaction.get("legs")).hasSize(2);
        assertThat(transaction.get("source").get("refType").asString()).isEqualTo("credit_grant");
        assertThat(transaction.get("source").get("page").asString()).isEqualTo("user");
        assertThat(transaction.get("source").get("pageId").asString())
                .isEqualTo(renter.id().toString());
        mvc.perform(admin.as(get("/admin/v1/ledger/transactions/" + UUID.randomUUID())))
                .andExpect(status().isNotFound());

        // Entries by cause and time, through the list engine.
        var entries = body(mvc.perform(admin.as(get("/admin/v1/ledger")
                        .param("account", "renter_wallet")
                        .param("owner", renter.id().toString())
                        .param("refType", "credit_grant")
                        .param("from", before.toString()))))
                .get("entries");
        assertThat(entries).hasSize(1);
    }

    @Test
    void everyLedgerCheckRunsInTheAppIsKeptAndATamperedWalletFailsItsCheck() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var run = body(mvc.perform(admin.as(post("/admin/v1/reconciliation"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"checking the ledger's checks\"}"))
                .andExpect(status().isOk()));
        // Every check, in order, each with its count. The database is every test's, and some leave shortcuts a check
        // finds (a top-up marked paid without its credit), so this test asserts only what it does itself.
        assertThat(run.get("checks")).hasSize(17);
        assertThat(run.get("checks").get(0).get("name").asString()).isEqualTo("each transaction balances");
        assertThat(run.get("checks").get(16).get("name").asString())
                .isEqualTo("every billed minute had both witnesses");
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.reconciliation_checks where run_id = ?::uuid",
                        Long.class,
                        run.get("id").asString()))
                .isEqualTo(17);

        var renter = TestUsers.signUp(mvc);
        grants.grant(
                renter.id(), Paise.of(500), "tampering test", UUID.randomUUID().toString(), OPERATOR);
        jdbc.update("update billing.wallets set balance_paise = balance_paise + 1 where user_id = ?", renter.id());
        try {
            var result = reconciliation.run("test");
            var wallets = result.checks().stream()
                    .filter(check -> check.position() == 4)
                    .findFirst()
                    .orElseThrow();
            assertThat(wallets.found()).isPositive();
            assertThat(result.mismatches())
                    .anySatisfy(mismatch -> assertThat(mismatch.userId()).isEqualTo(renter.id()));
        } finally {
            jdbc.update("update billing.wallets set balance_paise = balance_paise - 1 where user_id = ?", renter.id());
        }
    }

    @Test
    void theDatabaseRefusesAnUnbalancedTransactionAndAnEntryDatedInAClosedMonth() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> insert(UUID.randomUUID(), clock.instant(), 100, 99)))
                .hasMessageContaining("unbalanced");

        var month = LONG_AGO.plusMonths(1);
        jdbc.update(
                "insert into billing.periods (month, state, version, updated_at) values (?, 'closed', 1, now())"
                        + " on conflict (month) do update set state = 'closed'",
                month.atDay(1));
        try {
            var dated = month.atDay(15).atStartOfDay(Periods.INDIA).toInstant();
            assertThatThrownBy(() ->
                            transactions.executeWithoutResult(status -> insert(UUID.randomUUID(), dated, 100, 100)))
                    .hasMessageContaining("the month is closed");
            // The month before is open: the same entry dated in it is taken.
            var open = LONG_AGO.atDay(15).atStartOfDay(Periods.INDIA).toInstant();
            var txn = UUID.randomUUID();
            transactions.executeWithoutResult(status -> insert(txn, open, 100, 100));
            assertThat(jdbc.queryForObject(
                            "select count(*) from billing.ledger_entries where txn_id = ?", Long.class, txn))
                    .isEqualTo(2);
        } finally {
            jdbc.update("update billing.periods set state = 'open' where month = ?", month.atDay(1));
        }
    }

    /** Two entries straight into the ledger, platform accounts only, as only a test may: gateway fees from clearing. */
    private void insert(UUID txn, Instant at, long debit, long credit) {
        jdbc.update(
                "insert into billing.ledger_entries (id, txn_id, account, owner_id, amount_paise, direction, ref_type, ref_id, created_at)"
                        + " values (?, ?, 'gateway_fees', null, ?, 'debit', 'test', ?, ?),"
                        + " (?, ?, 'gateway_clearing', null, ?, 'credit', 'test', ?, ?)",
                UUID.randomUUID(),
                txn,
                debit,
                txn,
                Timestamp.from(at),
                UUID.randomUUID(),
                txn,
                credit,
                txn,
                Timestamp.from(at));
    }

    private static String adjustment(String template, String legs, String extra) {
        return "{\"template\":\"" + template + "\",\"legs\":[" + legs + "],\"reason\":\"a fee Razorpay kept\","
                + "\"reference\":\"ticket 42\"" + extra + "}";
    }

    private static String leg(String account, String owner, long paise, String direction) {
        return "{\"account\":\"" + account + "\"," + (owner == null ? "" : "\"ownerId\":\"" + owner + "\",")
                + "\"amountPaise\":" + paise + ",\"direction\":\"" + direction + "\"}";
    }

    @Test
    void anAdjustmentPostsOnlyOnceApprovedAndItsReversalLinksBack() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var asked = body(mvc.perform(admin.as(post("/admin/v1/adjustments"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustment(
                                "fee_correction",
                                leg("gateway_fees", null, 236, "debit") + ","
                                        + leg("gateway_clearing", null, 236, "credit"),
                                "")))
                .andExpect(status().isAccepted()));
        // Every leg is in the approver's diff, and nothing is posted yet: it waits, solo mode's delay included.
        assertThat(asked.get("changes")).hasSize(2);
        var request = jdbc.queryForMap(
                "select state, solo, extract(epoch from run_after - created_at) as delay from identity.approval_requests where id = ?::uuid",
                asked.get("id").asString());
        assertThat(request.get("state")).isEqualTo("pending");
        if (Boolean.TRUE.equals(request.get("solo"))) {
            assertThat(((Number) request.get("delay")).longValue())
                    .isGreaterThanOrEqualTo(Duration.ofHours(1).toSeconds());
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.adjustments where approval_id = ?::uuid",
                        Long.class,
                        asked.get("id").asString()))
                .isZero();

        TestAdmins.approve(mvc, jdbc, clock, asked.toString());
        var posted = body(mvc.perform(admin.as(get("/admin/v1/adjustments").param("template", "fee_correction"))))
                .get("adjustments")
                .get(0);
        assertThat(posted.get("approvalId").asString())
                .isEqualTo(asked.get("id").asString());
        assertThat(posted.get("legs")).hasSize(2);
        assertThat(posted.get("reference").asString()).isEqualTo("ticket 42");
        var id = posted.get("id").asString();

        var reverse = body(mvc.perform(admin.as(post("/admin/v1/adjustments/" + id + "/reverse"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"posted to the wrong month\",\"reference\":\"ticket 43\"}"))
                .andExpect(status().isAccepted()));
        TestAdmins.approve(mvc, jdbc, clock, reverse.toString());
        var original = body(mvc.perform(admin.as(get("/admin/v1/adjustments/" + id))));
        var reversalId = original.get("reversedById").asString();
        var reversal = body(mvc.perform(admin.as(get("/admin/v1/adjustments/" + reversalId))));
        assertThat(reversal.get("template").asString()).isEqualTo("reversal");
        assertThat(reversal.get("reversesId").asString()).isEqualTo(id);
        // The same legs, the other way round: the two transactions net to nothing.
        assertThat(jdbc.queryForObject(
                        "select coalesce(sum(case direction when 'debit' then amount_paise else -amount_paise end), 0)"
                                + " from billing.ledger_entries where account = 'gateway_fees' and txn_id in (?::uuid, ?::uuid)",
                        Long.class,
                        original.get("txnId").asString(),
                        reversal.get("txnId").asString()))
                .isZero();

        // Once reversed, it isn't reversed again.
        mvc.perform(admin.as(post("/admin/v1/adjustments/" + id + "/reverse"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"posted to the wrong month\",\"reference\":\"ticket 43\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnbalancedOrForbiddenAdjustmentIsRefusedBeforeAnyoneIsAsked() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var renter = TestUsers.signUp(mvc);
        var refusals = new String[] {
            adjustment(
                    "journal",
                    leg("gateway_fees", null, 100, "debit") + "," + leg("gateway_clearing", null, 99, "credit"),
                    ""),
            adjustment(
                    "journal",
                    leg("renter_purchased", renter.id().toString(), 100, "credit") + ","
                            + leg("promotions", null, 100, "debit"),
                    ""),
            adjustment(
                    "write_off",
                    leg("write_offs", null, 10_001, "debit") + "," + leg("gateway_clearing", null, 10_001, "credit"),
                    ""),
            adjustment(
                    "fee_correction",
                    leg("promotions", null, 100, "debit") + "," + leg("gateway_clearing", null, 100, "credit"),
                    ""),
            adjustment(
                    "journal",
                    leg("gateway_fees", null, 100, "debit") + "," + leg("gateway_clearing", null, 100, "credit"),
                    ",\"correctsMonth\":\"2018-01\""),
        };
        for (var refused : refusals) {
            mvc.perform(admin.as(post("/admin/v1/adjustments"))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(refused))
                    .andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.approval_requests where requested_by = ? and kind = 'money.adjustment'",
                        Long.class,
                        admin.id()))
                .isZero();
    }

    @Test
    void aGoodwillCreditIsAGrantAndItsReversalTakesItBack() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var renter = TestUsers.signUp(mvc);
        var asked = body(mvc.perform(admin.as(post("/admin/v1/adjustments"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustment(
                                "goodwill_credit",
                                leg("promotions", null, 2_500, "debit") + ","
                                        + leg("renter_wallet", renter.id().toString(), 2_500, "credit"),
                                "")))
                .andExpect(status().isAccepted()));
        TestAdmins.approve(mvc, jdbc, clock, asked.toString());
        mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.balancePaise")
                        .value(2_500));
        var id = jdbc.queryForObject(
                "select id from billing.adjustments where approval_id = ?::uuid",
                UUID.class,
                asked.get("id").asString());
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.credit_grants g join billing.adjustments a on a.txn_id = g.txn_id where a.id = ?",
                        Long.class,
                        id))
                .isOne();

        var reverse = body(mvc.perform(admin.as(post("/admin/v1/adjustments/" + id + "/reverse"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"given to the wrong renter\",\"reference\":\"ticket 44\"}"))
                .andExpect(status().isAccepted()));
        TestAdmins.approve(mvc, jdbc, clock, reverse.toString());
        mvc.perform(get("/v1/wallet").header("Authorization", renter.bearer()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.balancePaise")
                        .value(0));
    }

    @Test
    void aMonthClosesOnceItsChecklistHoldsByApprovalAndReopensWithAReason() throws Exception {
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        var month = LONG_AGO.toString();
        // Not over yet, this month isn't closed.
        var now = YearMonth.from(clock.instant().atZone(Periods.INDIA)).toString();
        mvc.perform(owner.as(post("/admin/v1/periods/" + now + "/close"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"closing the month early\"}"))
                .andExpect(status().isConflict());

        // A run that found nothing, as the checklist reads the latest: the shared database holds other tests'
        // shortcuts,
        // which some checks find (the checklist's refusal is aMonthIsntClosedWhileTheLedgersChecksFindSomething's).
        cleanRun();
        var period =
                body(mvc.perform(owner.as(get("/admin/v1/periods/" + month))).andExpect(status().isOk()));
        assertThat(period.get("state").asString()).isEqualTo("open");
        assertThat(period.get("over").asBoolean()).isTrue();
        assertThat(period.get("checklist")).isNotEmpty();
        for (var item : period.get("checklist")) {
            assertThat(item.get("done").asBoolean())
                    .as(item.get("name").asString())
                    .isTrue();
        }

        var close = body(mvc.perform(owner.as(post("/admin/v1/periods/" + month + "/close"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"" + period.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the CA has the month\"}"))
                .andExpect(status().isAccepted()));
        TestAdmins.approve(mvc, jdbc, clock, close.toString());
        try {
            var closed = body(mvc.perform(owner.as(get("/admin/v1/periods/" + month))));
            assertThat(closed.get("state").asString()).isEqualTo("closed");
            assertThat(closed.get("events").get(0).get("action").asString()).isEqualTo("closed");
            var dated = LONG_AGO.atDay(2).atStartOfDay(Periods.INDIA).toInstant();
            assertThatThrownBy(
                            () -> transactions.executeWithoutResult(status -> insert(UUID.randomUUID(), dated, 50, 50)))
                    .hasMessageContaining("the month is closed");

            // A correction to it is an adjustment in an open month that names it.
            var correction = body(mvc.perform(owner.as(post("/admin/v1/adjustments"))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(adjustment(
                                    "journal",
                                    leg("gateway_fees", null, 50, "debit") + ","
                                            + leg("gateway_clearing", null, 50, "credit"),
                                    ",\"correctsMonth\":\"" + month + "\"")))
                    .andExpect(status().isAccepted()));
            TestAdmins.approve(mvc, jdbc, clock, correction.toString());
            assertThat(jdbc.queryForObject(
                            "select corrects_month from billing.adjustments where approval_id = ?::uuid",
                            LocalDate.class,
                            correction.get("id").asString()))
                    .isEqualTo(LONG_AGO.atDay(1));
        } finally {
            var reopen = body(mvc.perform(owner.as(post("/admin/v1/periods/" + month + "/reopen"))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .header("If-Match", "\"" + (period.get("version").asLong() + 1) + "\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"the test reopens what it closed\"}"))
                    .andExpect(status().isAccepted()));
            TestAdmins.approve(mvc, jdbc, clock, reopen.toString());
        }
        var reopened = body(mvc.perform(owner.as(get("/admin/v1/periods/" + month))));
        assertThat(reopened.get("state").asString()).isEqualTo("open");
        assertThat(reopened.get("events")
                        .get(reopened.get("events").size() - 1)
                        .get("action")
                        .asString())
                .isEqualTo("reopened");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'period.reopen' and target_id = ?",
                        Long.class,
                        month))
                .isPositive();
    }

    /** A run of the ledger's checks that found nothing, recorded now, as the nightly run records one. */
    private void cleanRun() {
        recordedRun(0);
    }

    private void recordedRun(long found) {
        var run = UUID.randomUUID();
        jdbc.update(
                "insert into billing.reconciliation_runs (id, trigger, finished_at, ledger_debits_paise, ledger_credits_paise,"
                        + " mismatches) values (?, 'test', ?, 0, 0, 0)",
                run,
                Timestamp.from(clock.instant()));
        for (int position = 1; position <= 17; position++) {
            jdbc.update(
                    "insert into billing.reconciliation_checks (run_id, position, name, found) values (?, ?, ?, ?)",
                    run,
                    position,
                    "check " + position,
                    position == 4 ? found : 0);
        }
    }

    @Test
    void aMonthIsntClosedWhileTheLedgersChecksFindSomething() throws Exception {
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        recordedRun(2);
        var period =
                body(mvc.perform(owner.as(get("/admin/v1/periods/" + LONG_AGO))).andExpect(status().isOk()));
        var ledger = period.get("checklist").get(0);
        assertThat(ledger.get("name").asString()).isEqualTo("The ledger's checks pass");
        assertThat(ledger.get("done").asBoolean()).isFalse();
        assertThat(ledger.get("detail").asString()).isEqualTo("the latest run found 2 problems");
        mvc.perform(owner.as(post("/admin/v1/periods/" + LONG_AGO + "/close"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "\"" + period.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"the CA has the month\"}"))
                .andExpect(status().isConflict());
        cleanRun();
    }

    @Test
    void everyReportFigureTiesToTheLedgerEntriesItSums() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var today = LocalDate.ofInstant(clock.instant(), Periods.INDIA);
        var report = body(mvc.perform(admin.as(get("/admin/v1/money/report")
                        .param("interval", "month")
                        .param("from", today.withDayOfMonth(1).toString())
                        .param("to", today.toString())))
                .andExpect(status().isOk()));
        var start = today.withDayOfMonth(1).atStartOfDay(Periods.INDIA).toInstant();
        var end = today.plusDays(1).atStartOfDay(Periods.INDIA).toInstant();
        // The report and the sums in one snapshot: other tests' sessions post while this runs.
        var snapshot = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        snapshot.executeWithoutResult(status -> {
            var rows = reports.headlines(MoneyReports.Interval.MONTH, today.withDayOfMonth(1), today);
            for (var figure : MoneyReports.HEADLINES) {
                long expected = 0;
                for (var term : figure.terms()) {
                    expected += term.sign() * sum(term, start, end);
                }
                long reported = rows.stream()
                        .mapToLong(row -> row.figures().get(figure.name()))
                        .sum();
                assertThat(reported).as(figure.name()).isEqualTo(expected);
            }
        });
        assertThat(report.get("figures")).hasSize(MoneyReports.HEADLINES.size());

        mvc.perform(admin.as(get("/admin/v1/money/liabilities"))).andExpect(status().isOk());
        snapshot.executeWithoutResult(status -> {
            var at = clock.instant();
            var liabilities = reports.liabilities(at);
            var aged = liabilities.hostPayableAged();
            assertThat(aged.upTo7DaysPaise() + aged.days8To14Paise() + aged.days15To30Paise() + aged.over30DaysPaise())
                    .isEqualTo(liabilities.hostPayablePaise());
            assertThat(liabilities.renterFreePaise()).isEqualTo(accountBalance("renter_wallet", at));
            assertThat(liabilities.renterPurchasedPaise()).isEqualTo(accountBalance("renter_purchased", at));
        });
    }

    /** What a term sums, straight from the entries. */
    private long sum(MoneyReports.Term term, Instant start, Instant end) {
        String side = switch (term.side()) {
            case DEBITS -> "case direction when 'debit' then amount_paise else 0 end";
            case CREDITS -> "case direction when 'credit' then amount_paise else 0 end";
            case NET ->
                term.account().raisedByCredits
                        ? "case direction when 'credit' then amount_paise else -amount_paise end"
                        : "case direction when 'debit' then amount_paise else -amount_paise end";
        };
        return jdbc.queryForObject(
                "select coalesce(sum(" + side + "), 0) from billing.ledger_entries where account = ?"
                        + " and (cast(? as text) is null or ref_type = ?) and created_at >= ? and created_at < ?",
                Long.class,
                term.account().dbValue,
                term.refType(),
                term.refType(),
                Timestamp.from(start),
                Timestamp.from(end));
    }

    /** An account's balance from the entries dated up to {@code at}: a test may have dated some later and reset the clock. */
    private long accountBalance(String account, Instant at) {
        return jdbc.queryForObject(
                "select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)"
                        + " from billing.ledger_entries where account = ? and created_at <= ?",
                Long.class,
                account,
                Timestamp.from(at));
    }

    @Test
    void theDatabaseAllowsExactlyTheAccountsTheChartNames() {
        var constraint = jdbc.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint where conname = 'ledger_entries_account_check'",
                String.class);
        var named = new java.util.TreeSet<String>();
        var matcher = java.util.regex.Pattern.compile("'([a-z_]+)'").matcher(constraint);
        while (matcher.find()) {
            named.add(matcher.group(1));
        }
        var chart = new java.util.TreeSet<String>();
        for (var account : Account.values()) {
            chart.add(account.dbValue);
        }
        assertThat(named).isEqualTo(chart);
    }

    private static JsonNode find(JsonNode accounts, String name) {
        for (var account : accounts) {
            if (account.get("account").asString().equals(name)) {
                return account;
            }
        }
        throw new AssertionError("no account " + name);
    }

    private static long balance(JsonNode accounts, String name) {
        return find(accounts, name).get("balancePaise").asLong();
    }
}
