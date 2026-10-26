package com.idleo.controlplane.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.billing.SessionCharges;
import com.idleo.controlplane.billing.internal.TestCredit;
import gpu.agent.v1.Session.AbuseSignal;
import gpu.agent.v1.Session.UsageReport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Software no renter may run on a host's GPU (ADR 0038): what a host's runner reports becomes one review item for the
 * session; only a stop suspends the renter, and nothing else ever does; an admin confirms (perhaps a ban, which only
 * clearing lifts) or clears (the account back, the session's charge refunded in one ledger transaction); and a held
 * account is refused everything but its own data and support.
 */
@ControlPlaneTest
class AbuseReviewTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long CHARGED = 300;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    CreditReservations reservations;

    @Autowired
    SessionCharges charges;

    @Autowired
    AbuseReviews reviews;

    private TestUsers.TestUser renter;
    private UUID hostId;
    private TestAdmins.TestAdmin admin;

    @BeforeEach
    void setUp() throws Exception {
        renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        var owner = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        hostId = UUID.fromString(JSON.readTree(mvc.perform(post("/v1/hosts")
                                .header("Authorization", owner.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Mined-on laptop\"}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString());
        TestCredit.grant(context, renter.id(), 10_000);
        admin = TestAdmins.signIn(mvc, jdbc, clock);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** A "Run your code" session of the renter's, run by the host on attempt 1 and charged {@link #CHARGED}. */
    private UUID session(String template) {
        var sessionId = UUID.randomUUID();
        var now = Timestamp.from(clock.instant());
        jdbc.update(
                "insert into sessions.sessions (id, renter_id, host_id, type, state, attempt, template_id, image_digest,"
                        + " vram_needed_mb, max_price_paise_per_min, price_paise_per_min, max_spend_paise,"
                        + " max_runtime_seconds, idempotency_key, request_hash, state_changed_at, version, created_at,"
                        + " started_at, params, presets) values (?, ?, ?, 'batch', 'COMPLETED', 1, ?, 'image@sha256:1',"
                        + " 1024, 20, 12, 1000, 600, ?, 'hash', ?, 0, ?, ?, '{}', '{}')",
                sessionId,
                renter.id(),
                hostId,
                template,
                sessionId.toString(),
                now,
                now,
                now);
        jdbc.update(
                "insert into sessions.attempts (session_id, attempt, host_id, gpu_uuids, command_id, minute_index_base,"
                        + " created_at, price_paise_per_min) values (?, 1, ?, ?, ?, 0, ?, 12)",
                sessionId,
                hostId,
                new String[] {"GPU-abuse"},
                UUID.randomUUID(),
                now);
        transactions.executeWithoutResult(status -> reservations.reserve(renter.id(), sessionId, Paise.of(CHARGED)));
        transactions.executeWithoutResult(status -> charges.settle(new SessionCharges.Charge(
                sessionId,
                renter.id(),
                UUID.randomUUID(),
                List.of(new SessionCharges.Part(hostId, Paise.of(CHARGED))),
                Paise.ZERO)));
        transactions.executeWithoutResult(status -> reservations.release(sessionId));
        return sessionId;
    }

    private UUID runCode() {
        return session("run-code-simulated");
    }

    private static AbuseSignal signal(UUID session, AbuseSignal.Kind kind, String detail, boolean stopped) {
        return AbuseSignal.newBuilder()
                .setSessionId(session.toString())
                .setAttempt(1)
                .setKind(kind)
                .setDetail(detail)
                .setStopped(stopped)
                .build();
    }

    private String accountStatus(UUID user) {
        return jdbc.queryForObject("select status from identity.users where id = ?", String.class, user);
    }

    private long balance() {
        return jdbc.queryForObject(
                "select balance_paise from billing.wallets where user_id = ?", Long.class, renter.id());
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private int items(UUID session) {
        return jdbc.queryForObject(
                "select count(*) from trust.abuse_items where session_id = ?", Integer.class, session);
    }

    private UUID itemOf(UUID session) {
        return jdbc.queryForObject("select id from trust.abuse_items where session_id = ?", UUID.class, session);
    }

    private ResultActions admin(String action, UUID item, String json) throws Exception {
        return mvc.perform(admin.as(body(post("/admin/v1/abuse/" + item + "/" + action), json)));
    }

    private int audited(String action, UUID item) {
        return jdbc.queryForObject(
                "select count(*) from identity.audit_log where action = ? and target_id = ?",
                Integer.class,
                action,
                item.toString());
    }

    private void behave(UUID session, int minute, int util, int memory, int pcie, long net) {
        reviews.onBehaviour(
                hostId,
                UsageReport.newBuilder()
                        .setSessionId(session.toString())
                        .setAttempt(1)
                        .setMinuteIndex(minute)
                        .setGpuSeconds(60)
                        .setAvgUtilPct(util)
                        .setBehaviourReported(true)
                        .setAvgMemoryPct(memory)
                        .setAvgPcieKbPerS(pcie)
                        .setNetworkOutBytes(net / 2)
                        .setNetworkInBytes(net - net / 2)
                        .build());
    }

    // ---- What arrives ----

    @Test
    void aSessionTheRunnerStoppedOpensAnItemAndSuspendsTheRenterWhileItIsOpen() throws Exception {
        var session = runCode();
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_PROCESS_HASH, "a listed build", true));

        var item = itemOf(session);
        assertThat(jdbc.queryForMap("select state, action, banned from trust.abuse_items where id = ?", item))
                .containsEntry("state", "open")
                .containsEntry("action", "suspended")
                .containsEntry("banned", false);
        assertThat(accountStatus(renter.id())).isEqualTo("suspended");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'user.suspend' and target_id = ?",
                        Integer.class,
                        renter.id().toString()))
                .as("the suspension is audited, by the watch and with its item")
                .isOne();
        assertThat(jdbc.queryForObject(
                        "select reason from identity.audit_log where action = 'user.suspend' and target_id = ?",
                        String.class,
                        renter.id().toString()))
                .contains(item.toString());
    }

    @Test
    void aNameAFirstLookupAndDroppedConnectionsOnlyTellAnAdminAndSuspendNoOne() throws Exception {
        var session = runCode();
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_PROCESS_NAME, "xmrig", false));
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_POOL_LOOKUP, "nanopool.org", false));
        reviews.onAbuse(
                hostId,
                AbuseSignal.newBuilder()
                        .setSessionId(session.toString())
                        .setAttempt(1)
                        .setKind(AbuseSignal.Kind.KIND_CONNECTION_DROPS)
                        .setCount(4000)
                        .build());

        assertThat(accountStatus(renter.id())).isEqualTo("active");
        assertThat(jdbc.queryForObject(
                        "select action from trust.abuse_items where session_id = ?", String.class, session))
                .isEqualTo("none");
        var signals = jdbc.queryForList(
                "select kind from trust.abuse_signals where item_id = ? order by at, id",
                String.class,
                itemOf(session));
        assertThat(signals).containsExactlyInAnyOrder("process_name", "pool_lookup", "connection_drops");
        // The renter is not told, and can still book.
        mvc.perform(get("/v1/sessions").header("Authorization", renter.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void aSignalSentAgainBecauseItWasNotAckedIsOneSignalAndOneSuspension() throws Exception {
        var session = runCode();
        var stop = signal(session, AbuseSignal.Kind.KIND_POOL_LOOKUP, "2miners.com", true);
        reviews.onAbuse(hostId, stop);
        reviews.onAbuse(hostId, stop);
        reviews.onAbuse(hostId, stop);

        assertThat(jdbc.queryForObject(
                        "select count(*) from trust.abuse_signals where item_id = ?", Integer.class, itemOf(session)))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'user.suspend' and target_id = ?",
                        Integer.class,
                        renter.id().toString()))
                .isOne();
    }

    @Test
    void aHostCantReportAboutAnAttemptItDidNotRun() throws Exception {
        var session = runCode();
        var someoneElse = UUID.randomUUID();
        reviews.onAbuse(someoneElse, signal(session, AbuseSignal.Kind.KIND_PROCESS_HASH, "a listed build", true));
        behave(session, 0, 99, 1, 0, 1_000);
        reviews.onBehaviour(
                someoneElse,
                UsageReport.newBuilder()
                        .setSessionId(session.toString())
                        .setAttempt(1)
                        .setMinuteIndex(1)
                        .setBehaviourReported(true)
                        .build());

        assertThat(items(session)).isZero();
        assertThat(accountStatus(renter.id()))
                .as("a host can't get a renter suspended for a session it didn't run")
                .isEqualTo("active");
        assertThat(jdbc.queryForObject(
                        "select count(*) from trust.behavior_samples where session_id = ?", Integer.class, session))
                .as("only the host that ran it")
                .isOne();
    }

    @Test
    void aMiningLikePatternForFifteenMinutesOpensAnItemThatNeverStopsAnything() throws Exception {
        var session = runCode();
        // High GPU use, almost none of its memory, nothing over the bus, a small steady trickle over the network.
        for (int minute = 0; minute < AbuseReviews.PATTERN_MINUTES - 1; minute++) {
            behave(session, minute, 99, 3, 40, 3_000);
        }
        assertThat(items(session)).as("14 minutes are not 15").isZero();
        behave(session, AbuseReviews.PATTERN_MINUTES - 1, 99, 3, 40, 3_000);

        var item = itemOf(session);
        assertThat(jdbc.queryForObject("select kind from trust.abuse_signals where item_id = ?", String.class, item))
                .isEqualTo("mining_pattern");
        assertThat(jdbc.queryForObject("select action from trust.abuse_items where id = ?", String.class, item))
                .isEqualTo("none");
        assertThat(accountStatus(renter.id())).isEqualTo("active");
        // And a 16th minute is the same signal, not another.
        behave(session, AbuseReviews.PATTERN_MINUTES, 99, 3, 40, 3_000);
        assertThat(jdbc.queryForObject(
                        "select count(*) from trust.abuse_signals where item_id = ?", Integer.class, item))
                .isOne();
    }

    @Test
    void aJobThatDoesRealWorkOrIsNotRunYourCodeNeverLooksLikeMining() throws Exception {
        var training = runCode();
        for (int minute = 0; minute < 20; minute++) {
            // It uses the GPU hard and most of its memory, and moves data over the bus: training.
            behave(training, minute, 99, 70, 900_000, 3_000);
        }
        var burst = runCode();
        for (int minute = 0; minute < 20; minute++) {
            behave(burst, minute, minute == 7 ? 40 : 99, 3, 40, 3_000);
        }
        var quiet = runCode();
        for (int minute = 0; minute < 20; minute++) {
            behave(quiet, minute, 99, 3, 40, 0);
        }
        var fineTune = session("fine-tune-simulated");
        for (int minute = 0; minute < 20; minute++) {
            behave(fineTune, minute, 99, 3, 40, 3_000);
        }

        for (var session : List.of(training, burst, quiet, fineTune)) {
            assertThat(items(session)).isZero();
        }
    }

    // ---- What an admin decides ----

    @Test
    void confirmingKeepsTheSuspensionAndABanIsLiftedOnlyByClearingTheItem() throws Exception {
        var session = runCode();
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_PROCESS_HASH, "a listed build", true));
        var item = itemOf(session);

        admin("confirm", item, "{\"ban\":false,\"reason\":\"A miner, as the hash shows.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.state").value("confirmed"))
                .andExpect(jsonPath("$.item.banned").value(false));
        assertThat(accountStatus(renter.id())).isEqualTo("suspended");

        admin("confirm", item, "{\"ban\":true,\"reason\":\"Second time this month.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.banned").value(true));
        assertThat(accountStatus(renter.id())).isEqualTo("banned");
        assertThat(audited("abuse.confirm", item)).isEqualTo(2);

        // Reinstate on the Users page is for suspensions: it can't lift a ban.
        mvc.perform(admin.as(body(
                        post("/admin/v1/users/" + renter.id() + "/unsuspend"), "{\"reason\":\"they asked nicely\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/action-refused"));
        assertThat(accountStatus(renter.id())).isEqualTo("banned");

        // Confirming again with the same ban changes and audits nothing more.
        admin("confirm", item, "{\"ban\":true,\"reason\":\"Second time this month.\"}")
                .andExpect(status().isOk());
        assertThat(audited("abuse.confirm", item)).isEqualTo(2);
    }

    @Test
    void clearingReinstatesTheAccountAndRefundsTheSessionOnceInOneLedgerTransaction() throws Exception {
        var session = runCode();
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_PROCESS_HASH, "a listed build", true));
        var item = itemOf(session);
        admin("confirm", item, "{\"ban\":true,\"reason\":\"Looks like a miner.\"}")
                .andExpect(status().isOk());
        var before = balance();

        admin("clear", item, "{\"reason\":\"It was a test of their own tool.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.state").value("cleared"))
                .andExpect(jsonPath("$.item.banned").value(false))
                .andExpect(jsonPath("$.refundedPaise").value(CHARGED));

        assertThat(accountStatus(renter.id())).isEqualTo("active");
        assertThat(balance()).as("the charge, back").isEqualTo(before + CHARGED);
        var row = jdbc.queryForMap(
                "select refund_txn, refund_paise, resolved_by from trust.abuse_items where id = ?", item);
        assertThat(row.get("refund_paise")).isEqualTo(CHARGED);
        assertThat(row.get("resolved_by")).isEqualTo(admin.id());
        assertThat(jdbc.queryForObject(
                        "select count(*) from billing.ledger_entries where txn_id = ?",
                        Integer.class,
                        row.get("refund_txn")))
                .as("a balanced transaction: the renter, the commission, the host")
                .isGreaterThanOrEqualTo(2);
        assertThat(row.get("refund_txn")).isEqualTo(AbuseReviews.refundTxnId(item));
        // The audit row has the admin's typed reason and names the account only by id.
        assertThat(jdbc.queryForObject(
                        "select reason from identity.audit_log where action = 'abuse.clear' and target_id = ?",
                        String.class,
                        item.toString()))
                .isEqualTo("It was a test of their own tool.");
        assertThat(jdbc.queryForObject(
                        "select reason from identity.audit_log where action = 'user.hold_lifted' and target_id = ?",
                        String.class,
                        renter.id().toString()))
                .isEqualTo("It was a test of their own tool.");

        // Clearing again changes nothing: no second refund, no second audit row.
        admin("clear", item, "{\"reason\":\"It was a test of their own tool.\"}")
                .andExpect(status().isOk());
        assertThat(balance()).isEqualTo(before + CHARGED);
        assertThat(audited("abuse.clear", item)).isOne();
        // And a cleared item can't be confirmed afterwards.
        admin("confirm", item, "{\"ban\":false,\"reason\":\"Changed my mind.\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/action-refused"));

        var run = JSON.readTree(
                mvc.perform(admin.as(body(post("/admin/v1/reconciliation"), "{\"reason\":\"after an abuse refund\"}")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(run.get("ledgerBalanced").asBoolean()).isTrue();
        assertThat(run.get("mismatches").findValuesAsString("userId"))
                .doesNotContain(renter.id().toString());
    }

    @Test
    void aNoteIsItsOwnReasonAndTheQueueListsOpenItemsFirstWithTheirEvidence() throws Exception {
        var session = runCode();
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_POOL_LOOKUP, "nanopool.org", false));
        behave(session, 0, 98, 2, 10, 2_000);
        var item = itemOf(session);

        admin("notes", item, "{\"note\":\"Asked the host what they saw.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0].note").value("Asked the host what they saw."));
        assertThat(audited("abuse.note", item)).isOne();

        var queue = JSON.readTree(mvc.perform(admin.as(get("/admin/v1/abuse")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(queue.get("items").findValuesAsString("id")).contains(item.toString());
        var detail = JSON.readTree(mvc.perform(admin.as(get("/admin/v1/abuse/" + item)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(detail.get("signals").get(0).get("detail").asString()).isEqualTo("nanopool.org");
        assertThat(detail.get("item").get("kinds").get(0).asString()).isEqualTo("pool_lookup");
        assertThat(detail.get("behaviour").get(0).get("gpuPercent").asInt()).isEqualTo(98);
        assertThat(detail.get("paidPaise").asLong()).isEqualTo(CHARGED);
        mvc.perform(admin.as(get("/admin/v1/abuse?state=cleared")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void onlyAnAdminReadsOrDecidesAndAReasonIsRequired() throws Exception {
        var session = runCode();
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_PROCESS_NAME, "xmrig", false));
        var item = itemOf(session);

        mvc.perform(get("/admin/v1/abuse").header("Authorization", renter.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(body(post("/admin/v1/abuse/" + item + "/clear"), "{\"reason\":\"let me out please\"}")
                        .header("Authorization", renter.bearer()))
                .andExpect(status().isNotFound());
        admin("clear", item, "{\"reason\":\"ok\"}").andExpect(status().isBadRequest());
        admin("clear", item, "{\"reason\":\"write to nikki@example.com about it\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(admin.as(get("/admin/v1/abuse/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select state from trust.abuse_items where id = ?", String.class, item))
                .isEqualTo("open");
    }

    // ---- What a held account can still do ----

    @Test
    void aHeldAccountSeesItsStatusAndKeepsItsPrivacyRightsAndSupportAndNothingElse() throws Exception {
        var session = runCode();
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_PROCESS_HASH, "a listed build", true));
        var asRenter = renter.bearer();

        // The account, told it's suspended (a ban says the same); nothing that books, uploads or hosts.
        mvc.perform(get("/v1/me").header("Authorization", asRenter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("suspended"));
        for (var path : List.of("/v1/sessions", "/v1/wallet", "/v1/hosts", "/v1/notifications", "/v1/uploads")) {
            mvc.perform(get(path).header("Authorization", asRenter))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/problems/account-suspended"));
        }
        mvc.perform(post("/v1/sessions")
                        .header("Authorization", asRenter)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/account-suspended"));
        mvc.perform(get("/nothing-here").header("Authorization", asRenter)).andExpect(status().isForbidden());

        // Their data, their export, their deletion's standing and a message to support all still work.
        mvc.perform(get("/v1/me/data").header("Authorization", asRenter)).andExpect(status().isOk());
        mvc.perform(get("/v1/me/exports").header("Authorization", asRenter)).andExpect(status().isOk());
        mvc.perform(get("/v1/me/deletion").header("Authorization", asRenter)).andExpect(status().isOk());
        mvc.perform(post("/v1/support/tickets")
                        .header("Authorization", asRenter)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"account\",\"message\":\"I think this was a mistake.\","
                                + "\"replyTo\":\"" + "held-" + renter.id() + "@example.com" + "\",\"elapsedMs\":5000}"))
                .andExpect(status().isAccepted());

        // And once banned, the same.
        var item = itemOf(session);
        admin("confirm", item, "{\"ban\":true,\"reason\":\"Confirmed by the hash.\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/v1/me").header("Authorization", asRenter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("suspended"));
        mvc.perform(get("/v1/me/data").header("Authorization", asRenter)).andExpect(status().isOk());
        mvc.perform(get("/v1/sessions").header("Authorization", asRenter)).andExpect(status().isForbidden());

        // The admin console is shut to a held account too.
        mvc.perform(get("/admin/v1/session").header("Authorization", asRenter)).andExpect(status().isNotFound());
    }

    @Test
    void anOpenReviewStopsADeletionUntilAnAdminHasAnswered() throws Exception {
        var session = runCode();
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_POOL_LOOKUP, "nanopool.org", false));
        var item = itemOf(session);
        var asRenter = renter.bearer();

        var blockers = JSON.readTree(mvc.perform(get("/v1/me/deletion").header("Authorization", asRenter))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(blockers.toString()).contains("open-review");

        admin("clear", item, "{\"reason\":\"It was fine.\"}").andExpect(status().isOk());
        var after = JSON.readTree(mvc.perform(get("/v1/me/deletion").header("Authorization", asRenter))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(after.toString()).doesNotContain("open-review");
    }

    @Test
    void aSettledItemAndWhatEachJobDidAreDeletedAfterTheirPeriodsAndAnOpenOneNever() throws Exception {
        var settled = runCode();
        reviews.onAbuse(hostId, signal(settled, AbuseSignal.Kind.KIND_PROCESS_NAME, "xmrig", false));
        var settledItem = itemOf(settled);
        admin("clear", settledItem, "{\"reason\":\"It was fine.\"}").andExpect(status().isOk());
        var open = runCode();
        reviews.onAbuse(hostId, signal(open, AbuseSignal.Kind.KIND_PROCESS_NAME, "xmrig", false));
        behave(open, 0, 50, 50, 500, 500);

        var rules = context.getBeansOfType(com.idleo.controlplane.RetentionRule.class)
                .values();
        var later = Instant.now(clock).plus(Duration.ofDays(800));
        for (var rule : rules) {
            if (rule.name().equals("behaviour-samples") || rule.name().equals("abuse-reviews")) {
                rule.apply(later);
            }
        }

        assertThat(jdbc.queryForObject(
                        "select count(*) from trust.abuse_items where id = ?", Integer.class, settledItem))
                .as("a settled item, with its signals and notes, once a year has passed")
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from trust.abuse_signals where item_id = ?", Integer.class, settledItem))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from trust.abuse_items where session_id = ?", Integer.class, open))
                .as("an open one is never deleted")
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from trust.behavior_samples", Integer.class))
                .as("a minute's numbers are kept 90 days")
                .isZero();
    }

    // ---- Learning a build from a confirmed item (ADR 0038, ADR 0041) ----

    private static final String BUILD = "b".repeat(64);

    @Autowired
    LearnedBuilds learned;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @Autowired
    TransactionTemplate txTemplate;

    @Autowired
    com.idleo.controlplane.identity.AuditTrail audit;

    @Autowired
    com.idleo.controlplane.IdGenerator ids;

    /** A session whose runner flagged a program by name, and told which build it was. */
    private UUID flagged(String name, String sha256) {
        var session = runCode();
        reviews.onAbuse(
                hostId,
                AbuseSignal.newBuilder()
                        .setSessionId(session.toString())
                        .setAttempt(1)
                        .setKind(AbuseSignal.Kind.KIND_PROCESS_NAME)
                        .setDetail(name)
                        .setSha256(sha256)
                        .build());
        return itemOf(session);
    }

    private ResultActions addBuild(UUID item, String sha256, String kind, String reason) throws Exception {
        return admin(
                "blocklist",
                item,
                "{\"sha256\":\"%s\",\"kind\":\"%s\",\"reason\":\"%s\"}".formatted(sha256, kind, reason));
    }

    private void confirmed(UUID item) throws Exception {
        admin("confirm", item, "{\"ban\":false,\"reason\":\"It really was a miner.\"}")
                .andExpect(status().isOk());
    }

    @Test
    void aFlaggedProgramsBuildCanBeAddedFromAConfirmedItemAndIsAuditedWithTheAdminsReason() throws Exception {
        var item = flagged("renamedminer", BUILD);
        confirmed(item);

        addBuild(item, BUILD, "miner", "Seen mining on two hosts.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.additions.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.additions[?(@.sha256 == '" + BUILD + "')].name")
                        .value("renamedminer"))
                .andExpect(jsonPath("$.additions[?(@.sha256 == '" + BUILD + "')].published")
                        .value(false));

        assertThat(jdbc.queryForObject(
                        "select reason from identity.audit_log where action = 'abuse.add_to_blocklist' and target_id = ?",
                        String.class,
                        item.toString()))
                .isEqualTo("Seen mining on two hosts.");
        assertThat(learned.export())
                .anySatisfy(entry -> assertThat(entry)
                        .containsEntry("sha256", BUILD)
                        .containsEntry("name", "renamedminer")
                        .containsEntry("kind", "miner")
                        .containsEntry("item", item.toString()));
        mvc.perform(admin.as(get("/admin/v1/abuse/blocklist-additions"))).andExpect(status().isOk());
    }

    @Test
    void onlyAConfirmedItemAndOnlyABuildItSawCanBeAdded() throws Exception {
        var item = flagged("someminer", "e".repeat(64));

        // Not settled yet.
        addBuild(item, "e".repeat(64), "miner", "Looks like one.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/action-refused"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("confirmed")));
        confirmed(item);
        // A build the review never saw.
        addBuild(item, "f".repeat(64), "miner", "A guess.").andExpect(status().isConflict());
        // Invalid input.
        addBuild(item, "not-a-hash", "miner", "A guess.").andExpect(status().isBadRequest());
        addBuild(item, "e".repeat(64), "worm", "A guess.").andExpect(status().isBadRequest());
        addBuild(item, "e".repeat(64), "miner", "no").andExpect(status().isBadRequest());
        assertThat(learned.export()).noneSatisfy(entry -> assertThat(entry).containsEntry("sha256", "f".repeat(64)));
    }

    @Test
    void aBuildAlreadyOnTheListOrAlreadyAddedIsRefused() throws Exception {
        var session = runCode();
        reviews.onAbuse(hostId, signal(session, AbuseSignal.Kind.KIND_PROCESS_HASH, "a listed build", true));
        var stopped = itemOf(session);
        // A listed build's own hash is already on the list.
        jdbc.update(
                "insert into trust.abuse_signals (id, item_id, kind, detail, sha256, count, stopped, at)"
                        + " values (?, ?, 'process_name', 'x', ?, 0, false, now())",
                UUID.randomUUID(),
                stopped,
                "a".repeat(64));
        jdbc.update(
                "update trust.abuse_signals set sha256 = ? where item_id = ? and kind = 'process_hash'",
                "a".repeat(64),
                stopped);
        confirmed(stopped);
        addBuild(stopped, "a".repeat(64), "miner", "Already there.")
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already on the blocklist")));

        var item = flagged("twice", "9".repeat(64));
        confirmed(item);
        addBuild(item, "9".repeat(64), "miner", "First time.").andExpect(status().isOk());
        addBuild(item, "9".repeat(64), "miner", "Second time.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already waiting")));
    }

    @Test
    void takingABuildBackOffIsAuditedAndLeavesItOutOfWhatIsExported() throws Exception {
        var item = flagged("wrongcall", "8".repeat(64));
        confirmed(item);
        addBuild(item, "8".repeat(64), "miner", "We thought so.").andExpect(status().isOk());
        var addition = jdbc.queryForObject(
                "select id from trust.blocklist_additions where sha256 = ?", UUID.class, "8".repeat(64));

        mvc.perform(admin.as(body(
                        post("/admin/v1/abuse/blocklist-additions/" + addition + "/remove"),
                        "{\"reason\":\"It was a fine program.\"}")))
                .andExpect(status().isOk());

        assertThat(learned.export()).noneSatisfy(entry -> assertThat(entry).containsEntry("sha256", "8".repeat(64)));
        assertThat(audited("abuse.remove_from_blocklist", item)).isOne();
        mvc.perform(admin.as(body(
                        post("/admin/v1/abuse/blocklist-additions/" + addition + "/remove"),
                        "{\"reason\":\"Again, by mistake.\"}")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBuildShowsAsPublishedOnceTheSignedFileNamesIt() throws Exception {
        var item = flagged("liveminer", "7".repeat(64));
        confirmed(item);
        addBuild(item, "7".repeat(64), "miner", "Seen twice.").andExpect(status().isOk());
        assertThat(learned.list())
                .filteredOn(a -> a.sha256().equals("7".repeat(64)))
                .allMatch(a -> !a.published());

        // The same service, reading a feed that now names the build (the application's own feed is left alone).
        var feed = new com.idleo.controlplane.hosts.Blocklists() {
            @Override
            public java.util.Optional<Published> newerThan(String held) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Set<String> learnedBuilds() {
                return java.util.Set.of("7".repeat(64));
            }

            @Override
            public java.util.Optional<Programs> programs() {
                return java.util.Optional.empty();
            }
        };
        var reading = new LearnedBuilds(jdbcClient, txTemplate, reviews, audit, feed, ids, clock);

        assertThat(reading.list())
                .filteredOn(a -> a.sha256().equals("7".repeat(64)))
                .allMatch(a -> a.published());
    }
}
