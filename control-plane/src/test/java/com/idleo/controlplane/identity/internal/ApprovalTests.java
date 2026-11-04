package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Maker-checker (ADR 0051): the one engine, through the endpoints, for lifting a suspension and a role change. */
@ControlPlaneTest
class ApprovalTests {

    private static final String REASON = "{\"reason\":\"their appeal was upheld\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ApprovalEngine engine;

    private final JsonMapper json = JsonMapper.builder().build();

    /** Every test starts with no other holder of the permissions it uses. */
    @BeforeEach
    void noOtherAdmins() {
        jdbc.update("update identity.admins set locked_at = now() where locked_at is null");
        jdbc.update("update identity.approval_requests set state = 'cancelled' where state = 'pending'");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(REASON))
                .andReturn()
                .getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    private UUID suspendedPerson() throws Exception {
        var person = TestUsers.signUp(mvc);
        jdbc.update("update identity.users set status = 'suspended' where id = ?", person.id());
        return person.id();
    }

    private String status(UUID user) {
        return jdbc.queryForObject("select status from identity.users where id = ?", String.class, user);
    }

    private String state(String approval) {
        return jdbc.queryForObject(
                "select state from identity.approval_requests where id = ?::uuid", String.class, approval);
    }

    private String ask(TestAdmins.TestAdmin admin, UUID person) throws Exception {
        var asked = send(admin.as(post("/admin/v1/users/" + person + "/unsuspend")));
        assertThat(asked.getStatus()).as(asked.getContentAsString()).isEqualTo(202);
        return body(asked).get("id").asString();
    }

    @Test
    void anotherAdminApprovesAndItRunsAsItWasAsked() throws Exception {
        var person = suspendedPerson();
        var maker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var checker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var id = ask(maker, person);

        assertThat(status(person)).isEqualTo("suspended");
        var waiting = body(
                mvc.perform(checker.as(get("/admin/v1/approvals"))).andReturn().getResponse());
        assertThat(waiting.get("requests").findValuesAsString("id")).contains(id);
        var request = waiting.get("requests").get(0);
        assertThat(request.get("solo").asBoolean()).isFalse();
        assertThat(request.get("changes").get(0).get("to").asString()).isEqualTo("active");

        var approved = send(checker.as(post("/admin/v1/approvals/" + id + "/approve")));
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        assertThat(body(approved).get("state").asString()).isEqualTo("done");
        assertThat(status(person)).isEqualTo("active");

        // The action's own audit row is the requester's, and says which approval it ran under.
        var row = jdbc.queryForMap(
                "select actor_user_id, reason, approval_id::text as approval from identity.audit_log"
                        + " where action = 'user.unsuspend' and target_id = ?",
                person.toString());
        assertThat(row)
                .containsEntry("actor_user_id", maker.id())
                .containsEntry("reason", "their appeal was upheld")
                .containsEntry("approval", id);
    }

    @Test
    void theRequesterCanNeverApproveTheirOwnRequest() throws Exception {
        var person = suspendedPerson();
        var maker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var id = ask(maker, person);
        var refused = send(maker.as(post("/admin/v1/approvals/" + id + "/approve")));
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(body(refused).get("type").asString()).isEqualTo("/problems/action-refused");
        assertThat(state(id)).isEqualTo("pending");
        assertThat(status(person)).isEqualTo("suspended");
    }

    @Test
    void anAdminWithoutTheActionsPermissionCannotDecideIt() throws Exception {
        var person = suspendedPerson();
        var maker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var support = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        var id = ask(maker, person);
        var refused = send(support.as(post("/admin/v1/approvals/" + id + "/approve")));
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(body(refused).get("permission").asString()).isEqualTo("users.unsuspend");
    }

    @Test
    void aRejectedRequestDoesNothing() throws Exception {
        var person = suspendedPerson();
        var maker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var checker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var id = ask(maker, person);
        assertThat(send(checker.as(post("/admin/v1/approvals/" + id + "/reject")))
                        .getStatus())
                .isEqualTo(200);
        assertThat(state(id)).isEqualTo("rejected");
        assertThat(status(person)).isEqualTo("suspended");
        var again = send(checker.as(post("/admin/v1/approvals/" + id + "/approve")));
        assertThat(again.getStatus()).isIn(409, 412);
    }

    @Test
    void aTargetThatChangedMeanwhileIsRefused() throws Exception {
        var person = suspendedPerson();
        var maker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var checker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var id = ask(maker, person);
        jdbc.update("update identity.users set status = 'active' where id = ?", person);
        jdbc.update("update identity.users set status = 'suspended' where id = ?", person);

        var refused = send(checker.as(post("/admin/v1/approvals/" + id + "/approve")));
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(body(refused).get("detail").asString()).contains("changed");
        assertThat(state(id)).isEqualTo("failed");
        assertThat(status(person)).isEqualTo("suspended");
    }

    @Test
    void aRequestExpiresAfterSeventyTwoHours() throws Exception {
        var person = suspendedPerson();
        var maker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var checker = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        var id = ask(maker, person);
        clock.advance(Duration.ofHours(71));
        engine.sweep();
        assertThat(state(id)).isEqualTo("pending");
        clock.advance(Duration.ofHours(2));
        engine.sweep();
        assertThat(state(id)).isEqualTo("expired");
        assertThat(status(person)).isEqualTo("suspended");
        assertThat(checker.id()).isNotNull();
    }

    @Test
    void soloModeWaitsItsDelayTellsTheOwnerAndRunsAsSelfApproved() throws Exception {
        var person = suspendedPerson();
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        var id = ask(owner, person);
        var request = jdbc.queryForMap(
                "select solo, run_after - created_at as delay from identity.approval_requests where id = ?::uuid", id);
        assertThat(request.get("solo")).isEqualTo(true);

        org.awaitility.Awaitility.await()
                .until(() -> jdbc.queryForObject(
                                "select count(*) from notifications.notifications where user_id = ? and title like"
                                        + " 'An admin action will run%'",
                                Integer.class, owner.id())
                        == 1);

        clock.advance(Duration.ofHours(23));
        engine.sweep();
        assertThat(state(id)).isEqualTo("pending");
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        engine.sweep();
        assertThat(state(id)).isEqualTo("done");
        assertThat(status(person)).isEqualTo("active");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'approval.self_approve' and target_id = ?",
                        Integer.class,
                        id))
                .isEqualTo(1);
    }

    @Test
    void aSoloRequestCanBeCancelled() throws Exception {
        var person = suspendedPerson();
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        var id = ask(owner, person);
        assertThat(send(owner.as(post("/admin/v1/approvals/" + id + "/cancel"))).getStatus())
                .isEqualTo(200);
        clock.advance(Duration.ofHours(25));
        engine.sweep();
        assertThat(state(id)).isEqualTo("cancelled");
        assertThat(status(person)).isEqualTo("suspended");
    }

    @Test
    void soloModeSwitchesOffWhenASecondEligibleAdminExists() throws Exception {
        var person = suspendedPerson();
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        var id = ask(owner, person);
        var second = TestAdmins.signInAs(mvc, jdbc, clock, "trust_safety");
        engine.sweep();
        var row = jdbc.queryForMap("select solo, run_after from identity.approval_requests where id = ?::uuid", id);
        assertThat(row.get("solo")).isEqualTo(false);
        assertThat(row.get("run_after")).isNull();

        // So it no longer runs by itself: it waits for them.
        clock.advance(Duration.ofHours(25));
        engine.sweep();
        assertThat(state(id)).isEqualTo("pending");
        clock.reset();
        assertThat(send(second.as(post("/admin/v1/approvals/" + id + "/approve")))
                        .getStatus())
                .isEqualTo(200);
        assertThat(status(person)).isEqualTo("active");
    }

    @Test
    void aRoleChangeIsApprovedAuditedAndEndsTheirSessions() throws Exception {
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        var other = TestAdmins.signIn(mvc, jdbc, clock);
        var support = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        assertThat(send(support.as(post("/admin/v1/admins/" + support.id() + "/roles/finance/grant")))
                        .getStatus())
                .isEqualTo(403);

        var asked = send(owner.as(post("/admin/v1/admins/" + support.id() + "/roles/finance/grant")));
        assertThat(asked.getStatus()).isEqualTo(202);
        var id = body(asked).get("id").asString();
        assertThat(mvc.perform(support.as(get("/admin/v1/money/nothing")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        assertThat(send(other.as(post("/admin/v1/approvals/" + id + "/approve")))
                        .getStatus())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.admin_role_grants where user_id = ? and role_id = 'finance'",
                        Integer.class,
                        support.id()))
                .isEqualTo(1);
        // Their session is gone: the same 404 as anyone without one.
        assertThat(mvc.perform(support.as(get("/admin/v1/dashboard")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'admin.role_grant' and target_id = ?"
                                + " and approval_id = ?::uuid",
                        Integer.class,
                        support.id().toString(),
                        id))
                .isEqualTo(1);
    }

    @Test
    void theLastOwnerKeepsTheRole() throws Exception {
        jdbc.update("delete from identity.admin_role_grants where role_id = 'owner'");
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        var checker = TestAdmins.signInAs(mvc, jdbc, clock, "support");
        jdbc.update(
                "insert into identity.admin_role_permissions (role_id, permission) values ('support', 'admins.manage')");
        try {
            var asked = send(owner.as(post("/admin/v1/admins/" + owner.id() + "/roles/owner/remove")));
            var id = body(asked).get("id").asString();
            var refused = send(checker.as(post("/admin/v1/approvals/" + id + "/approve")));
            assertThat(refused.getStatus()).isEqualTo(409);
            assertThat(state(id)).isEqualTo("failed");
            assertThat(body(refused).get("detail").asString()).contains("last Owner");
        } finally {
            jdbc.update(
                    "delete from identity.admin_role_permissions where role_id = 'support' and permission = 'admins.manage'");
        }
    }

    @Test
    void stoppingThingsIsNeverGated() throws Exception {
        var person = TestUsers.signUp(mvc);
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        var suspended = send(owner.as(post("/admin/v1/users/" + person.id() + "/suspend")));
        assertThat(suspended.getStatus()).isEqualTo(200);
        assertThat(status(person.id())).isEqualTo("suspended");
    }

    @Test
    void aRetryThatPaidNothingSaysWhyInWords() throws Exception {
        var finance = TestAdmins.signInAs(mvc, jdbc, clock, "finance");
        var other = TestAdmins.signInAs(mvc, jdbc, clock, "finance");
        var asked = mvc.perform(finance.as(post("/admin/v1/payouts/retry"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + finance.id() + "\",\"reason\":\"their payout came back\"}"))
                .andReturn()
                .getResponse();
        assertThat(asked.getStatus()).isEqualTo(202);

        var refused = send(
                other.as(post("/admin/v1/approvals/" + body(asked).get("id").asString() + "/approve")));

        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(body(refused).get("detail").asString())
                .isEqualTo("It wasn't paid: there's no UPI ID payouts may go to.");
    }

    @Test
    void anApprovedExportIsFetchedOnceByWhoeverAskedAndIsNeverKept() throws Exception {
        var finance = TestAdmins.signInAs(mvc, jdbc, clock, "finance");
        var other = TestAdmins.signInAs(mvc, jdbc, clock, "finance");
        var asked = mvc.perform(finance.as(post("/admin/v1/payouts/tds-export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quarter\":\"2026-27-Q3\",\"reason\":\"for the CA this quarter\"}"))
                .andReturn()
                .getResponse();
        assertThat(asked.getStatus()).isEqualTo(202);
        var id = body(asked).get("id").asString();
        assertThat(send(other.as(post("/admin/v1/approvals/" + id + "/approve")))
                        .getStatus())
                .isEqualTo(200);

        assertThat(mvc.perform(other.as(post("/admin/v1/approvals/" + id + "/download")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        var file = mvc.perform(finance.as(post("/admin/v1/approvals/" + id + "/download")))
                .andReturn()
                .getResponse();
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentAsString()).startsWith("payout,pan,");
        assertThat(mvc.perform(finance.as(post("/admin/v1/approvals/" + id + "/download")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);
    }
}
