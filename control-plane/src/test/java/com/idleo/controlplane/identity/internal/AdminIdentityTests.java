package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.OperatorCommand;
import com.idleo.controlplane.SecretScan;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.AuditTrail;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The identity module's admin actions (ADR 0027): users found, suspended and reinstated; invite codes made. */
@ControlPlaneTest
class AdminIdentityTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    UserSuspensions suspensions;

    @Autowired
    InviteService invites;

    @Autowired
    TransactionTemplate transactions;

    private final JsonMapper json = JsonMapper.builder().build();

    private static MockHttpServletRequestBuilder reason(MockHttpServletRequestBuilder request, String reason) {
        return request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}");
    }

    private JsonNode read(MockHttpServletRequestBuilder request, TestAdmins.TestAdmin admin) throws Exception {
        return json.readTree(mvc.perform(admin.as(request))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private int auditRows(String action, String target) {
        return jdbc.queryForObject(
                "select count(*) from identity.audit_log where action = ? and target_id = ?",
                Integer.class,
                action,
                target);
    }

    private String email(UUID userId) {
        return jdbc.queryForObject("select email from identity.users where id = ?", String.class, userId);
    }

    @Test
    void findsUsersByPartOfTheirEmailOrTheirIdAndPagesThem() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var first = TestUsers.signUp(mvc);
        var second = TestUsers.signUp(mvc);
        var part = email(first.id()).substring(0, 20);
        var found = read(get("/admin/v1/users").param("query", part), admin);
        assertThat(found.get("users")).hasSize(1);
        assertThat(found.get("users").get(0).get("id").asString())
                .isEqualTo(first.id().toString());
        assertThat(found.get("users").get(0).get("status").asString()).isEqualTo("active");
        assertThat(read(get("/admin/v1/users").param("query", second.id().toString()), admin)
                        .get("users")
                        .get(0)
                        .get("id")
                        .asString())
                .isEqualTo(second.id().toString());

        var pageOne = read(get("/admin/v1/users").param("limit", "1"), admin);
        var pageTwo = read(
                get("/admin/v1/users")
                        .param("limit", "1")
                        .param("cursor", pageOne.get("nextCursor").asString()),
                admin);
        assertThat(pageOne.get("users").get(0).get("id").asString())
                .isGreaterThan(pageTwo.get("users").get(0).get("id").asString());

        var self = read(get("/admin/v1/users/" + admin.id()), admin);
        assertThat(self.get("admin").asBoolean()).isTrue();
        assertThat(self.get("lastSignInAt").isNull()).isFalse();
        mvc.perform(admin.as(get("/admin/v1/users/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(admin.as(get("/admin/v1/users").param("cursor", "nonsense")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void suspendingRefusesTheAccountAtOnceAndReinstatingLetsItBackEachAuditedOnce() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var renter = TestUsers.signUp(mvc);
        var target = renter.id().toString();

        mvc.perform(admin.as(reason(post("/admin/v1/users/" + target + "/suspend"), "spam")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
        var suspended =
                read(reason(post("/admin/v1/users/" + target + "/suspend"), "card testing from this account"), admin);
        assertThat(suspended.get("status").asString()).isEqualTo("suspended");
        mvc.perform(get("/v1/me").header("Authorization", renter.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/account-suspended"));
        // Again changes nothing and isn't audited twice.
        read(reason(post("/admin/v1/users/" + target + "/suspend"), "card testing from this account"), admin);
        assertThat(auditRows("user.suspend", target)).isOne();
        var row = jdbc.queryForMap(
                "select actor_kind, actor_user_id, reason, before->>'status' as before, after->>'status' as after"
                        + " from identity.audit_log where action = 'user.suspend' and target_id = ?",
                target);
        assertThat(row)
                .containsEntry("actor_kind", "admin")
                .containsEntry("actor_user_id", admin.id())
                .containsEntry("reason", "card testing from this account")
                .containsEntry("before", "active")
                .containsEntry("after", "suspended");

        read(reason(post("/admin/v1/users/" + target + "/unsuspend"), "they explained the payments"), admin);
        mvc.perform(get("/v1/me").header("Authorization", renter.bearer())).andExpect(status().isOk());
        assertThat(auditRows("user.unsuspend", target)).isOne();

        mvc.perform(admin.as(reason(post("/admin/v1/users/" + admin.id() + "/suspend"), "testing myself")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/action-refused"));
    }

    @Test
    void aSuspensionAndItsAuditRowRollBackTogether() throws Exception {
        var renter = TestUsers.signUp(mvc);
        transactions.executeWithoutResult(status -> {
            suspensions.suspend(renter.id(), "rolled back", AuditTrail.Actor.operator("tester"));
            assertThat(auditRows("user.suspend", renter.id().toString())).isOne();
            status.setRollbackOnly();
        });
        assertThat(auditRows("user.suspend", renter.id().toString())).isZero();
        assertThat(jdbc.queryForObject("select status from identity.users where id = ?", String.class, renter.id()))
                .isEqualTo("active");
    }

    @Test
    void invitesAndTheirAuditRowRollBackTogether() {
        var note = "rolled back " + UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            invites.create(2, note, java.util.Optional.empty(), AuditTrail.Actor.operator("tester"));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.invite_codes where note = ?", Integer.class, note))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from identity.audit_log where reason = ?", Integer.class, note))
                .isZero();
    }

    @Test
    void invitesAreShownOnceListedWithWhoUsedThemAndAuditedWithoutTheCodes() throws Exception {
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        var key = UUID.randomUUID().toString();
        var body = "{\"count\":3,\"note\":\"Pune pilot students\",\"expiresInDays\":30}";
        var created = json.readTree(mvc.perform(admin.as(post("/admin/v1/invites"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var codes = new ArrayList<String>();
        created.get("codes").forEach(code -> codes.add(code.asString()));
        assertThat(codes).hasSize(3).allMatch(code -> code.startsWith("IDLE-"));
        assertThat(created.get("expiresAt").isNull()).isFalse();
        mvc.perform(admin.as(post("/admin/v1/invites"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/secret-shown-once"));

        // One is used; the list says by whom, and shows no code.
        var renter = TestUsers.signUp(mvc);
        jdbc.update("update identity.users set status = 'waiting' where id = ?", renter.id());
        assertThat(invites.activate(renter.id(), codes.get(1))).isEqualTo(InviteService.Result.ACTIVATED);
        var used = read(get("/admin/v1/invites").param("used", "true"), admin).get("invites");
        assertThat(used.get(0).get("usedBy").asString()).isEqualTo(renter.id().toString());
        assertThat(used.get(0).get("usedByEmail").asString()).isEqualTo(email(renter.id()));
        assertThat(used.get(0).get("note").asString()).isEqualTo("Pune pilot students");
        assertThat(used.get(0).get("createdBy").asString()).startsWith("admin ");
        assertThat(read(get("/admin/v1/invites").param("used", "false").param("limit", "2"), admin)
                        .get("invites"))
                .hasSize(2);

        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'invite.create' and actor_user_id = ?"
                                + " and jsonb_array_length(after->'ids') = 3",
                        Integer.class,
                        admin.id()))
                .isOne();
        assertThat(SecretScan.tablesContaining(jdbc, codes.toArray(String[]::new)))
                .isEmpty();
    }

    @Test
    void createInvitesStillWorksThroughTheSameService() {
        var out = new ByteArrayOutputStream();
        new InviteCommands()
                .createInvites(invites)
                .run(
                        OperatorCommand.Arguments.parse("create-invites", "--count", "2", "--note", "command check"),
                        "nikki",
                        new PrintStream(out, true, StandardCharsets.UTF_8));
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("made 2 invite codes");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where action = 'invite.create'"
                                + " and actor_kind = 'operator' and actor_name = 'nikki' and reason = 'command check'",
                        Integer.class))
                .isOne();
    }
}
