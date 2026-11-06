package com.idleo.controlplane.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.AccountLimits;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.WorkQueue;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.sessions.UserUploads;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The enforcement ladder and appeals (ADR 0053). */
@ControlPlaneTest
class EnforcementTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcClient client;

    @Autowired
    MutableClock clock;

    @Autowired
    Enforcements enforcements;

    @Autowired
    Appeals appeals;

    @Autowired
    AccountLimits limits;

    @Autowired
    List<WorkQueue> queues;

    @Autowired
    IdGenerator ids;

    @Autowired
    AuditTrail audit;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    UserUploads uploads;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private MockHttpServletResponse send(TestAdmins.TestAdmin admin, MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(admin.as(request)).andReturn().getResponse();
    }

    private MockHttpServletResponse send(TestAdmins.TestAdmin admin, String path, Object body) throws Exception {
        return send(admin, post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode body(MockHttpServletResponse answer) throws Exception {
        assertThat(answer.getStatus()).as(answer.getContentAsString()).isLessThan(300);
        return json.readTree(answer.getContentAsString());
    }

    private JsonNode step(TestAdmins.TestAdmin admin, UUID user, Map<String, Object> extra) throws Exception {
        var asked = new java.util.HashMap<String, Object>(extra);
        asked.put("userId", user);
        asked.put("reason", "it broke the rules twice");
        return body(send(admin, "/admin/v1/enforcements", asked));
    }

    private String status(UUID user) {
        return jdbc.queryForObject("select status from identity.users where id = ?", String.class, user);
    }

    private List<String> told(UUID user) {
        return jdbc.queryForList(
                "select title from notifications.notifications where user_id = ? order by created_at",
                String.class,
                user);
    }

    private JsonNode appeal(TestUsers.TestUser person, String enforcement) throws Exception {
        var answer = mvc.perform(post("/v1/me/enforcements/" + enforcement + "/appeal")
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"It wasn't me: my brother used the laptop.\"}"))
                .andReturn()
                .getResponse();
        return body(answer);
    }

    private UUID appealId(String enforcement) {
        return jdbc.queryForObject(
                "select id from trust.appeals where enforcement_id = ?", UUID.class, UUID.fromString(enforcement));
    }

    @Test
    void eachStepTellsThePersonFromItsTemplateWithAWayToAppeal() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var steps = Map.of(
                "renter-warn",
                Map.<String, Object>of("template", "renter-warn"),
                "renter-limit",
                Map.<String, Object>of("template", "renter-limit", "limitKind", "no-custom-images"),
                "renter-suspend",
                Map.<String, Object>of(
                        "template",
                        "renter-suspend",
                        "endsAt",
                        clock.instant().plus(Duration.ofDays(7)).toString()),
                "renter-ban",
                Map.<String, Object>of("template", "renter-ban"));
        for (var asked : steps.entrySet()) {
            var person = TestUsers.signUp(mvc).id();
            var made = step(owner, person, asked.getValue());
            var title = jdbc.queryForObject(
                    "select title from trust.enforcement_templates where id = ?", String.class, asked.getKey());
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () -> assertThat(told(person)).as(asked.getKey()).contains(title));
            assertThat(jdbc.queryForObject(
                            "select body from notifications.notifications where user_id = ? and title = ?",
                            String.class,
                            person,
                            title))
                    .contains("appeal")
                    .contains("it broke the rules twice")
                    .contains(made.get("clause").asString());
        }
    }

    @Test
    void suspendingAndBanningStopAtOnceAndASuspensionNeedsAnEndDate() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUp(mvc).id();
        assertThat(send(
                                owner,
                                "/admin/v1/enforcements",
                                Map.of("userId", person, "template", "renter-suspend", "reason", "it broke the rules"))
                        .getStatus())
                .isEqualTo(400);
        step(
                owner,
                person,
                Map.of(
                        "template",
                        "renter-suspend",
                        "endsAt",
                        clock.instant().plus(Duration.ofDays(1)).toString()));
        assertThat(status(person)).isEqualTo("suspended");

        var other = TestUsers.signUp(mvc).id();
        step(owner, other, Map.of("template", "renter-ban"));
        assertThat(status(other)).isEqualTo("banned");
        assertThat(send(
                                owner,
                                "/admin/v1/enforcements",
                                Map.of("userId", owner.id(), "template", "renter-warn", "reason", "testing myself"))
                        .getStatus())
                .isEqualTo(409);
    }

    @Test
    void aSuspensionEndsOnItsDateAndTheAccountIsBack() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUp(mvc).id();
        var made = step(
                owner,
                person,
                Map.of(
                        "template",
                        "host-suspend",
                        "endsAt",
                        clock.instant().plus(Duration.ofDays(2)).toString()));
        enforcements.sweep();
        assertThat(status(person)).isEqualTo("suspended");
        clock.advance(Duration.ofDays(2).plusMinutes(1));
        enforcements.sweep();
        assertThat(status(person)).isEqualTo("active");
        assertThat(enforcements
                        .find(UUID.fromString(made.get("id").asString()))
                        .orElseThrow()
                        .state())
                .isEqualTo("ended");
    }

    @Test
    void aLimitIsWhatItSaysAndEndsWithIt() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUp(mvc).id();
        assertThat(limits.noCustomImages(person)).isFalse();
        step(
                owner,
                person,
                Map.of(
                        "template",
                        "renter-limit",
                        "limitKind",
                        "no-custom-images",
                        "endsAt",
                        clock.instant().plus(Duration.ofDays(1)).toString()));
        assertThat(limits.noCustomImages(person)).isTrue();

        step(owner, person, Map.of("template", "renter-limit", "limitKind", "session-hours-cap", "limitHours", 10));
        assertThat(limits.sessionHoursCap(person).getAsInt()).isEqualTo(10);

        assertThat(send(
                                owner,
                                "/admin/v1/enforcements",
                                Map.of(
                                        "userId",
                                        person,
                                        "template",
                                        "renter-limit",
                                        "limitKind",
                                        "payouts-held",
                                        "reason",
                                        "for a renter's misconduct"))
                        .getStatus())
                .isEqualTo(400);
        step(owner, person, Map.of("template", "host-limit", "limitKind", "payouts-held"));
        assertThat(limits.payoutsHeld(person)).isTrue();

        clock.advance(Duration.ofDays(2));
        enforcements.sweep();
        assertThat(limits.noCustomImages(person)).isFalse();
        assertThat(limits.payoutsHeld(person)).isTrue();
    }

    @Test
    void strikesCountForEachRoleSeparatelyAndExpire() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUp(mvc).id();
        step(owner, person, Map.of("template", "renter-warn"));
        step(owner, person, Map.of("template", "renter-warn"));
        step(owner, person, Map.of("template", "host-warn"));
        assertThat(enforcements.strikes(person, "renter")).isEqualTo(2);
        assertThat(enforcements.strikes(person, "host")).isEqualTo(1);
        var page = body(send(owner, get("/admin/v1/enforcements").param("user", person.toString())));
        assertThat(page.get("renterStrikes").asLong()).isEqualTo(2);
        assertThat(page.get("enforcements")).hasSize(3);

        clock.advance(Duration.ofDays(181));
        assertThat(enforcements.strikes(person, "renter")).isZero();
        assertThat(enforcements.strikes(person, "host")).isZero();
    }

    @Test
    void liftingGoesToAnotherAdmin() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var approver = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUp(mvc).id();
        var made =
                step(owner, person, Map.of("template", "renter-ban")).get("id").asString();
        var asked =
                send(owner, "/admin/v1/enforcements/" + made + "/lift", Map.of("reason", "we had the wrong person"));
        assertThat(asked.getStatus()).isEqualTo(202);
        assertThat(status(person)).isEqualTo("banned");
        body(send(
                approver,
                "/admin/v1/approvals/" + body(asked).get("id").asString() + "/approve",
                Map.of("reason", "we had the wrong person")));
        assertThat(status(person)).isEqualTo("active");
    }

    @Test
    void aHeldPersonAppealsOnceAndItLandsInTheInboxWithTheHistory() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUpLasting(mvc, Duration.ofDays(1));
        var made = step(owner, person.id(), Map.of("template", "renter-ban"))
                .get("id")
                .asString();

        var mine = body(mvc.perform(get("/v1/me/enforcements").header("Authorization", person.bearer()))
                .andReturn()
                .getResponse());
        assertThat(mine.get("enforcements").get(0).get("id").asString()).isEqualTo(made);

        var appealed = appeal(person, made);
        assertThat(appealed.get("appeal").asString()).isEqualTo("open");
        var again = mvc.perform(post("/v1/me/enforcements/" + made + "/appeal")
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Again.\"}"))
                .andReturn()
                .getResponse();
        assertThat(again.getStatus()).isEqualTo(409);

        var someoneElse = TestUsers.signUp(mvc);
        var refused = mvc.perform(post("/v1/me/enforcements/" + made + "/appeal")
                        .header("Authorization", someoneElse.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Not mine.\"}"))
                .andReturn()
                .getResponse();
        assertThat(refused.getStatus()).isEqualTo(404);

        var id = appealId(made);
        var queue = queues.stream()
                .filter(each -> each.type().equals("appeal"))
                .findFirst()
                .orElseThrow();
        assertThat(queue.open()).anyMatch(item -> item.sourceId().equals(id.toString()));
        var record = body(send(owner, get("/admin/v1/appeals/" + id)));
        assertThat(record.get("enforcement").get("id").asString()).isEqualTo(made);
        assertThat(record.get("history")).hasSize(1);
    }

    @Test
    void anAppealAttachesOnlyThePersonsOwnFilesAndAtMostThree() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUpLasting(mvc, Duration.ofDays(1));
        var made = step(owner, person.id(), Map.of("template", "renter-warn"))
                .get("id")
                .asString();
        var send = (java.util.function.Function<String, Integer>) attachments -> {
            try {
                return mvc.perform(post("/v1/me/enforcements/" + made + "/appeal")
                                .header("Authorization", person.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"body\":\"Look at this.\",\"attachments\":" + attachments + "}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertThat(send.apply("[\"" + UUID.randomUUID() + "\"]")).isEqualTo(400);
        var four = java.util.stream.Stream.generate(() -> "\"" + UUID.randomUUID() + "\"")
                .limit(4)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        assertThat(send.apply(four)).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                        "select count(*) from trust.appeals where enforcement_id = ?",
                        Integer.class,
                        UUID.fromString(made)))
                .isZero();
    }

    private MockHttpServletResponse upload(TestUsers.TestUser person, String body) throws Exception {
        return mvc.perform(post("/v1/uploads")
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    @Test
    void aSuspendedAccountUploadsOnlyAnAppealsFileWithinItsCaps() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUpLasting(mvc, Duration.ofDays(1));
        step(owner, person.id(), Map.of("template", "renter-ban"));

        var files = upload(person, "{\"name\":\"model\",\"files\":[{\"path\":\"a.bin\",\"sizeBytes\":10}]}");
        assertThat(files.getStatus()).isEqualTo(403);
        assertThat(files.getContentAsString()).contains("account-suspended");
        var two = upload(
                person,
                "{\"name\":\"proof\",\"purpose\":\"appeal\",\"files\":[{\"path\":\"a.png\",\"sizeBytes\":10},"
                        + "{\"path\":\"b.png\",\"sizeBytes\":10}]}");
        assertThat(two.getStatus()).isEqualTo(400);
        var big = upload(
                person,
                "{\"name\":\"proof\",\"purpose\":\"appeal\",\"files\":[{\"path\":\"a.png\",\"sizeBytes\":"
                        + (10L * 1024 * 1024 + 1) + "}]}");
        assertThat(big.getStatus()).isEqualTo(400);
        for (int i = 0; i < 3; i++) {
            var made = upload(
                    person,
                    "{\"name\":\"proof " + i
                            + "\",\"purpose\":\"appeal\",\"files\":[{\"path\":\"proof.png\",\"sizeBytes\":10}]}");
            assertThat(made.getStatus()).as(made.getContentAsString()).isEqualTo(201);
        }
        var fourth = upload(
                person,
                "{\"name\":\"proof 4\",\"purpose\":\"appeal\",\"files\":[{\"path\":\"proof.png\",\"sizeBytes\":10}]}");
        assertThat(fourth.getStatus()).isEqualTo(409);
    }

    @Test
    void anAdminOpensAnAttachmentByALinkAndEachOpeningIsLogged() throws Exception {
        var owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var reviewer = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUpLasting(mvc, Duration.ofDays(1));
        var made = upload(
                person,
                "{\"name\":\"proof\",\"purpose\":\"appeal\",\"files\":[{\"path\":\"receipt.png\",\"sizeBytes\":10}]}");
        assertThat(made.getStatus()).as(made.getContentAsString()).isEqualTo(201);
        var file =
                json.readTree(made.getContentAsString()).get("upload").get("id").asString();
        jdbc.update(
                "update sessions.uploads set status = 'ready', completed_at = now() where id = ?",
                UUID.fromString(file));
        var enforcement = step(owner, person.id(), Map.of("template", "renter-warn"))
                .get("id")
                .asString();
        body(mvc.perform(post("/v1/me/enforcements/" + enforcement + "/appeal")
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Here's the receipt.\",\"attachments\":[\"" + file + "\"]}"))
                .andReturn()
                .getResponse());
        var id = appealId(enforcement);

        var opened = body(send(
                reviewer,
                "/admin/v1/appeals/" + id + "/attachments/" + file + "/open",
                Map.of("reason", "reading the appeal's receipt")));
        assertThat(opened.get("url").asString()).contains("receipt.png");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.personal_data_access where admin_id = ? and subject_user_id = ? and field = 'appeal_attachment'",
                        Integer.class,
                        reviewer.id(),
                        person.id()))
                .isEqualTo(1);
        assertThat(send(
                                reviewer,
                                "/admin/v1/appeals/" + id + "/attachments/" + UUID.randomUUID() + "/open",
                                Map.of("reason", "reading the appeal's receipt"))
                        .getStatus())
                .isEqualTo(404);
    }

    @Test
    void whoeverEnforcedCantDecideItsAppealWhileAnotherAdminCan() throws Exception {
        var enforcer = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var other = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUpLasting(mvc, Duration.ofDays(1));
        var made = step(enforcer, person.id(), Map.of("template", "renter-warn"))
                .get("id")
                .asString();
        appeal(person, made);
        var id = appealId(made);

        var mine = send(
                enforcer, "/admin/v1/appeals/" + id + "/decide", Map.of("outcome", "uphold", "reason", "it was right"));
        assertThat(mine.getStatus()).isEqualTo(409);
        assertThat(mine.getContentAsString()).contains("another admin must decide");

        var decided = body(send(
                other, "/admin/v1/appeals/" + id + "/decide", Map.of("outcome", "uphold", "reason", "it was right")));
        assertThat(decided.get("state").asString()).isEqualTo("upheld");
        assertThat(decided.get("sameReviewer").asBoolean()).isFalse();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(told(person.id())).contains("Your appeal: we've kept our decision"));
    }

    /** With no other admin who could, the enforcer decides, and it says so on the decision and in the audit log. */
    @Test
    void aloneTheEnforcerDecidesAndThatsRecordedOnTheDecisionAndInTheAuditLog() throws Exception {
        var enforcer = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUpLasting(mvc, Duration.ofDays(1));
        var made = step(enforcer, person.id(), Map.of("template", "renter-warn"))
                .get("id")
                .asString();
        appeal(person, made);
        var id = appealId(made);

        var alone = new AdminAccess() {
            @Override
            public Set<AdminPermission> permissions(UUID admin) {
                return Set.of(AdminPermission.APPEALS_DECIDE);
            }

            @Override
            public List<Mentionable> mentionable() {
                return List.of(new Mentionable(enforcer.id(), "the only admin"));
            }
        };
        var solo = new Appeals(client, ids, clock, audit, alone, events, enforcements, uploads, transactionManager);
        var actor = new AuditTrail.Actor(
                AuditTrail.Actor.Kind.ADMIN, enforcer.id(), enforcer.id().toString());
        var decided = solo.uphold(id, "there was no one else to ask", actor);

        assertThat(decided.sameReviewer()).isTrue();
        assertThat(jdbc.queryForObject(
                        "select after::text from identity.audit_log where action = 'appeal.uphold' and target_id = ?",
                        String.class,
                        id.toString()))
                .contains("\"sameReviewer\": true");
        assertThatThrownBy(() -> solo.uphold(id, "and again", actor)).isInstanceOf(ApiProblem.class);
    }

    @Test
    void reversingGoesThroughTheApprovedLiftAndTellsThePerson() throws Exception {
        var enforcer = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var reviewer = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var approver = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var person = TestUsers.signUpLasting(mvc, Duration.ofDays(1));
        var made = step(
                        enforcer,
                        person.id(),
                        Map.of(
                                "template",
                                "renter-suspend",
                                "endsAt",
                                clock.instant().plus(Duration.ofDays(30)).toString()))
                .get("id")
                .asString();
        appeal(person, made);
        var id = appealId(made);

        var asked = send(
                reviewer,
                "/admin/v1/appeals/" + id + "/decide",
                Map.of("outcome", "reverse", "reason", "we had the wrong person"));
        assertThat(asked.getStatus()).as(asked.getContentAsString()).isEqualTo(202);
        assertThat(status(person.id())).isEqualTo("suspended");
        assertThat(jdbc.queryForObject("select state from trust.appeals where id = ?", String.class, id))
                .isEqualTo("open");

        body(send(
                approver,
                "/admin/v1/approvals/" + body(asked).get("id").asString() + "/approve",
                Map.of("reason", "agreed, wrong person")));
        assertThat(status(person.id())).isEqualTo("active");
        assertThat(jdbc.queryForObject("select state from trust.appeals where id = ?", String.class, id))
                .isEqualTo("reversed");
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(told(person.id())).contains("Your appeal: we've reversed our decision"));
    }
}
