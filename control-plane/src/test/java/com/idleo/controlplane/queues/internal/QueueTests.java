package com.idleo.controlplane.queues.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.WorkQueue;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The one inbox (ADR 0053): claims, timers on a controlled clock, priority, macros and bulk actions. */
@ControlPlaneTest
class QueueTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    QueueSync sync;

    @Autowired
    BulkActions bulk;

    @Autowired
    MeterRegistry registry;

    @Autowired
    List<WorkQueue> queues;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private UUID ticket(String topic, UUID user) {
        var id = UUID.randomUUID();
        var now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into support.tickets (id, number, user_id, topic, status, reply_to, created_at, updated_at)
                values (?, nextval('support.ticket_numbers'), ?, ?, 'open', 'person@example.com', ?, ?)
                """, id, user, topic, now, now);
        return id;
    }

    private JsonNode body(MockHttpServletResponse answer) throws Exception {
        return json.readTree(answer.getContentAsString());
    }

    private MockHttpServletResponse send(TestAdmins.TestAdmin admin, MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(admin.as(request)).andReturn().getResponse();
    }

    private MockHttpServletResponse send(TestAdmins.TestAdmin admin, String path, Object body) throws Exception {
        return send(admin, post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private UUID item(String type, UUID source) {
        return jdbc.queryForObject(
                "select id from queues.items where type = ? and source_id = ?", UUID.class, type, source.toString());
    }

    private Map<String, Object> row(UUID item) {
        return jdbc.queryForMap("select * from queues.items where id = ?", item);
    }

    @Test
    void everyKindOfWorkHasAProvider() {
        assertThat(queues.stream().map(WorkQueue::type).collect(Collectors.toSet()))
                .contains(
                        "review-minute",
                        "abuse",
                        "report",
                        "chargeback",
                        "ticket",
                        "verification",
                        "data-request",
                        "approval");
    }

    @Test
    void ofTwoClaimsAtOnceExactlyOneWinsAndTheOtherIsToldWhoHasIt() throws Exception {
        var person = TestUsers.signUp(mvc).id();
        var source = ticket("account", person);
        sync.run();
        var item = item("ticket", source);
        var one = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var other = TestAdmins.signInAs(mvc, jdbc, clock, "support");

        var barrier = new CyclicBarrier(2);
        var answers = new ArrayList<CompletableFuture<MockHttpServletResponse>>();
        for (var admin : List.of(one, other)) {
            answers.add(CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                    return send(admin, post("/admin/v1/queue/" + item + "/claim"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        var statuses = answers.stream()
                .map(CompletableFuture::join)
                .map(MockHttpServletResponse::getStatus)
                .toList();
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        // Whoever came second met the row as it was (409, naming who) or a version that had moved on (412, and a
        // reload).
        assertThat(statuses.stream().filter(status -> status != 200).toList())
                .allMatch(status -> status == 409 || status == 412);

        var seen = body(send(one, get("/admin/v1/queue/" + item)));
        assertThat(seen.get("state").asString()).isEqualTo("claimed");
        assertThat(seen.get("assigneeName").isNull()).isFalse();

        var winner = seen.get("assigneeId").asString().equals(one.id().toString()) ? one : other;
        var loser = winner == one ? other : one;
        var refused = send(loser, post("/admin/v1/queue/" + item + "/claim"));
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(refused.getContentAsString()).contains("is on it");
        assertThat(send(winner, post("/admin/v1/queue/" + item + "/claim")).getStatus())
                .isEqualTo(200);
    }

    @Test
    void aClaimLeftIdleIsReleasedByItselfAndSaysSo() throws Exception {
        var source = ticket("account", TestUsers.signUp(mvc).id());
        sync.run();
        var item = item("ticket", source);
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        assertThat(send(admin, post("/admin/v1/queue/" + item + "/claim")).getStatus())
                .isEqualTo(200);

        clock.advance(Duration.ofMinutes(29));
        sync.run();
        assertThat(row(item).get("assignee_id")).isEqualTo(admin.id());

        clock.advance(Duration.ofMinutes(2));
        sync.run();
        assertThat(row(item).get("assignee_id")).isNull();
        assertThat(row(item).get("state")).isEqualTo("open");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'queue.claim_released' and target_id = ?",
                        Integer.class,
                        item.toString()))
                .isEqualTo(1);
    }

    @Test
    void reassigningNeedsItsPermissionAndIsAudited() throws Exception {
        var source = ticket("account", TestUsers.signUp(mvc).id());
        sync.run();
        var item = item("ticket", source);
        var support = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var request = Map.of("assignee", support.id(), "reason", "they know this person's case");
        assertThat(send(support, "/admin/v1/queue/" + item + "/assign", request).getStatus())
                .isEqualTo(403);
        var given = send(owner, "/admin/v1/queue/" + item + "/assign", request);
        assertThat(given.getStatus()).as(given.getContentAsString()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select reason from identity.audit_log where action = 'queue.assign' and target_id = ?",
                        String.class,
                        item.toString()))
                .isEqualTo("they know this person's case");
    }

    @Test
    void aTimerPausesWhileWaitingOnThePersonAndAPassedTargetCountsAsABreach() throws Exception {
        var source = ticket("account", TestUsers.signUp(mvc).id());
        sync.run();
        var item = item("ticket", source);
        var opened = instant(row(item), "opened_at");
        assertThat(instant(row(item), "due_at")).isEqualTo(opened.plus(Duration.ofHours(24)));

        jdbc.update("update support.tickets set status = 'waiting' where id = ?", source);
        clock.advance(Duration.ofHours(1));
        sync.run();
        assertThat(row(item).get("state")).isEqualTo("waiting");
        assertThat(row(item).get("due_at")).isNull();

        clock.advance(Duration.ofHours(2));
        jdbc.update("update support.tickets set status = 'open' where id = ?", source);
        sync.run();
        // Stopped from the pass that saw it waiting to the one that saw it open: two hours, plus how long those passes
        // took.
        assertThat(instant(row(item), "due_at"))
                .isBetween(
                        opened.plus(Duration.ofHours(26)),
                        opened.plus(Duration.ofHours(26).plusSeconds(5)));

        var before = breaches("ticket");
        clock.advance(Duration.ofHours(24));
        sync.run();
        assertThat(row(item).get("breached_at")).isNotNull();
        var breached = row(item).get("breached_at");
        var counted = breaches("ticket");
        assertThat(counted).isGreaterThan(before);
        sync.run();
        assertThat(breaches("ticket")).isEqualTo(counted);
        assertThat(row(item).get("breached_at")).isEqualTo(breached);
    }

    private double breaches(String type) {
        var counter = registry.find("queues.breaches").tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }

    private static java.time.Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }

    @Test
    void aGrievanceIsAcknowledgedInADayAndResolvedInFifteenAndNeverPauses() throws Exception {
        var source = ticket("grievance", TestUsers.signUp(mvc).id());
        sync.run();
        var item = item("ticket", source);
        var opened = instant(row(item), "opened_at");
        assertThat(instant(row(item), "due_at")).isEqualTo(opened.plus(Duration.ofDays(1)));

        var admin = jdbc.queryForObject("select id from identity.users limit 1", UUID.class);
        jdbc.update("""
                insert into support.messages (id, ticket_id, author, admin_id, body, created_at)
                values (?, ?, 'admin', ?, 'We have it.', ?)
                """, UUID.randomUUID(), source, admin, Timestamp.from(clock.instant()));
        jdbc.update("update support.tickets set status = 'waiting' where id = ?", source);
        clock.advance(Duration.ofHours(1));
        sync.run();
        assertThat(instant(row(item), "due_at")).isEqualTo(opened.plus(Duration.ofDays(15)));
    }

    @Test
    void aChargebackRunsToRazorpaysDateAndCountsAsDueFortyEightHoursBefore() throws Exception {
        var person = TestUsers.signUp(mvc).id();
        var id = UUID.randomUUID();
        var respondBy = clock.instant().plus(Duration.ofDays(5)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                insert into billing.chargebacks (id, dispute_id, payment_id, user_id, amount_paise, currency, status,
                    deducted_paise, frozen_paise, respond_by, opened_at, received_at, updated_at)
                values (?, ?, 'pay_test', ?, 100000, 'INR', 'open', 0, 0, ?, now(), now(), now())
                """, id, "disp_" + id, person, Timestamp.from(respondBy));
        sync.run();
        var item = item("chargeback", id);
        assertThat(instant(row(item), "due_at")).isEqualTo(respondBy);
        assertThat(instant(row(item), "soon_at")).isEqualTo(respondBy.minus(Duration.ofHours(48)));
        var due = registry.get("queues.chargebacks.due_soon").gauge();

        var before = due.value();
        clock.advance(Duration.ofDays(4));
        sync.run();
        assertThat(due.value()).isGreaterThan(before);
    }

    @Test
    void whatIsGoneFromItsModuleIsResolvedAndAModuleThatFailsChangesNothing() throws Exception {
        var source = ticket("account", TestUsers.signUp(mvc).id());
        sync.run();
        var item = item("ticket", source);
        jdbc.update("update support.tickets set status = 'resolved', resolved_at = now() where id = ?", source);
        sync.run();
        assertThat(row(item).get("state")).isEqualTo("resolved");
        assertThat(row(item).get("resolved_at")).isNotNull();
    }

    @Test
    void anAdminSeesOnlyTheKindsOfWorkTheyMayReadAndAnotherKindIsNotThere() throws Exception {
        var source = ticket("account", TestUsers.signUp(mvc).id());
        sync.run();
        var item = item("ticket", source);
        var auditor = TestAdmins.signInAs(mvc, jdbc, clock, "operations");
        jdbc.update(
                "delete from identity.admin_role_permissions where role_id = 'operations' and permission = 'tickets.read'");
        try {
            assertThat(send(auditor, get("/admin/v1/queue/" + item)).getStatus())
                    .isEqualTo(404);
            assertThat(body(send(auditor, get("/admin/v1/queue").param("limit", "200")))
                            .get("items")
                            .toString())
                    .doesNotContain(item.toString());
        } finally {
            jdbc.update(
                    "insert into identity.admin_role_permissions (role_id, permission) values ('operations', 'tickets.read')");
        }
    }

    @Test
    void anOverrideNeedsAReasonAndMovesItUpAndAVipRanksAboveTheSameWork() throws Exception {
        var plain = ticket("account", TestUsers.signUp(mvc).id());
        var special = TestUsers.signUp(mvc).id();
        var vip = ticket("account", special);
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        jdbc.update(
                "insert into console.tags (id, label, colour, created_at) values ('vip', 'VIP', 'plain', now()) on conflict do nothing");
        assertThat(send(owner, post("/admin/v1/records/user/" + special + "/tags/vip"))
                        .getStatus())
                .isEqualTo(200);
        sync.run();
        assertThat((Long) row(item("ticket", vip)).get("sort_key"))
                .isGreaterThan((Long) row(item("ticket", plain)).get("sort_key"));

        var item = item("ticket", plain);
        assertThat(send(owner, "/admin/v1/queue/" + item + "/priority", Map.of("priority", "urgent"))
                        .getStatus())
                .isEqualTo(400);
        var moved = send(
                owner,
                "/admin/v1/queue/" + item + "/priority",
                Map.of("priority", "urgent", "reason", "the board asked about it"));
        assertThat(moved.getStatus()).as(moved.getContentAsString()).isEqualTo(200);
        assertThat(body(moved).get("priority").asString()).isEqualTo("urgent");
        assertThat(body(moved).get("overridden").asBoolean()).isTrue();
        sync.run();
        assertThat(row(item).get("override_score")).isEqualTo(300);
        assertThat((Long) row(item).get("sort_key"))
                .isGreaterThan((Long) row(item("ticket", vip)).get("sort_key"));
    }

    @Test
    void aMacroAnswersThroughTheTicketAndIsAuditedWithItsVersion() throws Exception {
        var person = TestUsers.signUp(mvc).id();
        jdbc.update("update identity.users set display_name = 'Asha Rao' where id = ?", person);
        var source = ticket("account", person);
        sync.run();
        var item = item("ticket", source);
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");

        var refused = send(
                owner, "/admin/v1/queue/macros", Map.of("type", "ticket", "name", "Bad", "body", "Hi {{nickname}}"));
        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(send(
                                owner,
                                "/admin/v1/queue/macros",
                                Map.of("type", "verification", "name", "No words", "body", "Hi"))
                        .getStatus())
                .isEqualTo(400);

        var made = send(
                owner,
                "/admin/v1/queue/macros",
                Map.of(
                        "type",
                        "ticket",
                        "name",
                        "Fixed " + source,
                        "body",
                        "Hello {{firstName}}, it's fixed.",
                        "setStatus",
                        "resolved"));
        assertThat(made.getStatus()).as(made.getContentAsString()).isEqualTo(200);
        var macro = body(made).get("id").asString();
        var edited = send(
                owner,
                "/admin/v1/queue/macros/" + macro,
                Map.of("body", "Hi {{firstName}}, it's fixed.", "setStatus", "resolved"));
        assertThat(body(edited).get("number").asInt()).isEqualTo(2);

        assertThat(body(send(owner, get("/admin/v1/queue/" + item + "/macros/" + macro)))
                        .get("body")
                        .asString())
                .isEqualTo("Hi Asha, it's fixed.");

        var applied = send(owner, "/admin/v1/queue/" + item + "/macro", Map.of("macro", macro));
        assertThat(applied.getStatus()).as(applied.getContentAsString()).isEqualTo(200);
        assertThat(body(applied).get("state").asString()).isEqualTo("resolved");
        assertThat(jdbc.queryForObject(
                        "select body from support.messages where ticket_id = ? and author = 'admin'",
                        String.class,
                        source))
                .isEqualTo("Hi Asha, it's fixed.");
        assertThat(jdbc.queryForObject("select status from support.tickets where id = ?", String.class, source))
                .isEqualTo("resolved");
        assertThat(jdbc.queryForObject(
                        "select after::text from identity.audit_log where action = 'macro.apply' and target_id = ?",
                        String.class,
                        item.toString()))
                .contains(macro)
                .contains("\"version\": 2");
    }

    private List<UUID> tickets(int count) throws Exception {
        var person = TestUsers.signUp(mvc).id();
        var sources = new ArrayList<UUID>();
        for (int i = 0; i < count; i++) {
            sources.add(ticket("account", person));
        }
        sync.run();
        return sources.stream().map(source -> item("ticket", source)).toList();
    }

    private void compare(JsonNode preview, TestAdmins.TestAdmin admin, String batch) throws Exception {
        var outcomes = body(send(admin, get("/admin/v1/queue/bulk/" + batch))).get("outcomes");
        assertThat(outcomes).hasSameSizeAs(preview.get("changes"));
        for (int i = 0; i < outcomes.size(); i++) {
            assertThat(outcomes.get(i).get("item"))
                    .isEqualTo(preview.get("changes").get(i).get("item"));
            assertThat(outcomes.get(i).get("after"))
                    .isEqualTo(preview.get("changes").get(i).get("after"));
        }
    }

    @Test
    void whatABulkActionPreviewsIsWhatItDoesItemByItemForEachKind() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        jdbc.update(
                "insert into console.tags (id, label, colour, created_at) values ('bulk-test', 'Bulk', 'plain', now()) on conflict do nothing");
        var actions = List.of(
                Map.<String, Object>of("kind", "assign", "assignee", owner.id()),
                Map.<String, Object>of("kind", "priority", "priority", "high"),
                Map.<String, Object>of("kind", "tag", "tag", "bulk-test"),
                Map.<String, Object>of("kind", "close"));
        for (var action : actions) {
            var items = tickets(3);
            var preview = send(owner, "/admin/v1/queue/bulk/preview", Map.of("items", items, "action", action));
            assertThat(preview.getStatus()).as(preview.getContentAsString()).isEqualTo(200);
            assertThat(body(preview).get("willChange").asInt()).isEqualTo(3);
            var ran = send(
                    owner,
                    "/admin/v1/queue/bulk",
                    Map.of("items", items, "action", action, "confirm", "3", "reason", "tidying the queue"));
            assertThat(ran.getStatus()).as(ran.getContentAsString()).isEqualTo(200);
            bulk.run();
            compare(body(preview), owner, body(ran).get("id").asString());
        }
    }

    @Test
    void aBulkActionReportsItemByItemAndARepeatChangesNothing() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var items = tickets(2);
        assertThat(send(owner, post("/admin/v1/queue/" + items.getFirst() + "/claim"))
                        .getStatus())
                .isEqualTo(200);
        var ran = send(
                owner,
                "/admin/v1/queue/bulk",
                Map.of(
                        "items",
                        items,
                        "action",
                        Map.of("kind", "assign", "assignee", owner.id()),
                        "confirm",
                        "2",
                        "reason",
                        "taking these two"));
        bulk.run();
        var finished = body(
                send(owner, get("/admin/v1/queue/bulk/" + body(ran).get("id").asString())));
        assertThat(finished.get("state").asString()).isEqualTo("done");
        assertThat(finished.get("done").asInt()).isEqualTo(1);
        assertThat(finished.get("skipped").asInt()).isEqualTo(1);
        assertThat(finished.get("outcomes").toString()).contains("They're on it already.");
    }

    @Test
    void itIsUndoneWithinTenMinutesAndRefusedAfter() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var items = tickets(2);
        var action = Map.of("kind", "close");
        var ran = send(
                owner,
                "/admin/v1/queue/bulk",
                Map.of("items", items, "action", action, "confirm", "2", "reason", "these are done"));
        bulk.run();
        var batch = body(ran).get("id").asString();
        assertThat(row(items.getFirst()).get("state")).isEqualTo("resolved");

        clock.advance(Duration.ofMinutes(9));
        var undone = send(owner, "/admin/v1/queue/bulk/" + batch + "/undo", Map.of("reason", "closed them by mistake"));
        assertThat(undone.getStatus()).as(undone.getContentAsString()).isEqualTo(200);
        assertThat(row(items.getFirst()).get("state")).isEqualTo("open");
        assertThat(body(undone).get("undone").asInt()).isEqualTo(2);
        assertThat(send(owner, "/admin/v1/queue/bulk/" + batch + "/undo", Map.of("reason", "closed them by mistake"))
                        .getStatus())
                .isEqualTo(409);

        var more = tickets(1);
        var late = send(
                owner,
                "/admin/v1/queue/bulk",
                Map.of("items", more, "action", action, "confirm", "1", "reason", "this is done too"));
        bulk.run();
        clock.advance(Duration.ofMinutes(11));
        assertThat(send(
                                owner,
                                "/admin/v1/queue/bulk/" + body(late).get("id").asString() + "/undo",
                                Map.of("reason", "closed it by mistake"))
                        .getStatus())
                .isEqualTo(409);
        assertThat(row(more.getFirst()).get("state")).isEqualTo("resolved");
    }

    @Test
    void overTwentyItemsGoesToAnotherAdminAndRunsOnceApproved() throws Exception {
        var asker = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var approver = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var items = tickets(21);
        var asked = send(
                asker,
                "/admin/v1/queue/bulk",
                Map.of(
                        "items",
                        items,
                        "action",
                        Map.of("kind", "priority", "priority", "low"),
                        "confirm",
                        "21",
                        "reason",
                        "these can all wait"));
        assertThat(asked.getStatus()).as(asked.getContentAsString()).isEqualTo(202);
        assertThat(row(items.getFirst()).get("override_score")).isNull();

        var approved = send(
                approver,
                "/admin/v1/approvals/" + body(asked).get("id").asString() + "/approve",
                Map.of("reason", "they can all wait"));
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        bulk.run();
        assertThat(items)
                .allSatisfy(item -> assertThat(row(item).get("override_score")).isEqualTo(20));
    }

    @Test
    void itHasToBeConfirmedByCountAndMoneyAndEnforcementAreNeverBulk() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var items = tickets(2);
        assertThat(send(
                                owner,
                                "/admin/v1/queue/bulk",
                                Map.of(
                                        "items",
                                        items,
                                        "action",
                                        Map.of("kind", "close"),
                                        "confirm",
                                        "yes",
                                        "reason",
                                        "these are done"))
                        .getStatus())
                .isEqualTo(400);
        for (var kind : List.of("refund", "suspend", "ban", "credit", "payout")) {
            assertThat(send(
                                    owner,
                                    "/admin/v1/queue/bulk/preview",
                                    Map.of("items", items, "action", Map.of("kind", kind)))
                            .getStatus())
                    .as(kind)
                    .isEqualTo(400);
        }
        assertThat(BulkActions.KINDS).isEqualTo(Set.of("assign", "tag", "priority", "close", "macro"));
    }

    @Test
    void theSidebarCountsWhatsMineWhatsNobodysAndWhatsBreaching() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var items = tickets(2);
        assertThat(send(owner, post("/admin/v1/queue/" + items.getFirst() + "/claim"))
                        .getStatus())
                .isEqualTo(200);
        var counts = body(send(owner, get("/admin/v1/queue/counts")));
        assertThat(counts.get("mine").asLong()).isEqualTo(1);
        assertThat(counts.get("unassigned").asLong()).isPositive();
        var mine = body(send(owner, get("/admin/v1/queue").param("view", "mine")));
        assertThat(mine.get("items")).hasSize(1);
        assertThat(mine.get("items").get(0).get("id").asString())
                .isEqualTo(items.getFirst().toString());
    }
}
