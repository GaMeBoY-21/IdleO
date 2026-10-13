package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.OperatorCommand;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.AuditTrail;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The admin role (ADR 0027): granted and taken only by operator command, audited, by verified email. */
@ControlPlaneTest
class AdminRolesTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AdminRoles roles;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;

    private String run(OperatorCommand command, String... args) {
        var out = new ByteArrayOutputStream();
        command.run(OperatorCommand.Arguments.parse(args), "nikki", new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    private String email(UUID userId) {
        return jdbc.queryForObject("select email from identity.users where id = ?", String.class, userId);
    }

    private int audited(String action, UUID userId) {
        return jdbc.queryForObject(
                "select count(*) from identity.audit_log where action = ? and target_id = ? and actor_name = 'nikki'"
                        + " and actor_kind = 'operator'",
                Integer.class,
                action,
                userId.toString());
    }

    @Test
    void grantAdminAndRevokeAdminWorkByEmailOnceEachAndAreAudited() throws Exception {
        var user = TestUsers.signUp(mvc);
        var commands = new AdminCommands();
        var grant = commands.grantAdmin(roles);
        var revoke = commands.revokeAdmin(roles);
        var email = email(user.id());

        assertThat(run(grant, "grant-admin", email.toUpperCase(java.util.Locale.ROOT), "--reason", "runs the pilot"))
                .contains("is now an admin");
        assertThat(run(grant, "grant-admin", email, "--reason", "again")).contains("already an admin");
        assertThat(audited("admin.grant", user.id())).isOne();
        assertThat(jdbc.queryForObject(
                        "select granted_by from identity.admins where user_id = ?", String.class, user.id()))
                .isEqualTo("nikki");

        assertThat(run(revoke, "revoke-admin", email, "--reason", "left the team"))
                .contains("no longer an admin");
        assertThat(run(revoke, "revoke-admin", email, "--reason", "again")).contains("isn't an admin");
        assertThat(audited("admin.revoke", user.id())).isOne();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.admins where user_id = ?", Integer.class, user.id()))
                .isZero();
    }

    @Test
    void refusesWithoutAReasonOrAnOperandAndForSomeoneWhoCantBeAnAdmin() throws Exception {
        var grant = new AdminCommands().grantAdmin(roles);
        assertThatThrownBy(() -> run(grant, "grant-admin", "--reason", "x")).isInstanceOf(OperatorCommand.Usage.class);
        assertThatThrownBy(() -> run(grant, "grant-admin", "someone@example.com"))
                .isInstanceOf(OperatorCommand.Usage.class);
        assertThatThrownBy(() ->
                        run(grant, "grant-admin", "nobody-" + UUID.randomUUID() + "@example.com", "--reason", "x"))
                .isInstanceOf(OperatorCommand.Refused.class)
                .hasMessageContaining("has signed in");
        var suspended = TestUsers.signUp(mvc);
        jdbc.update("update identity.users set status = 'suspended' where id = ?", suspended.id());
        assertThatThrownBy(() -> roles.grant(email(suspended.id()), "x", AuditTrail.Actor.operator("nikki")))
                .isInstanceOf(AdminRoles.Refused.class)
                .hasMessageContaining("suspended");
    }

    @Test
    void aGrantAndItsAuditRowRollBackTogether() throws Exception {
        var user = TestUsers.signUp(mvc);
        transactions.executeWithoutResult(status -> {
            roles.grant(email(user.id()), "rolled back", AuditTrail.Actor.operator("nikki"));
            status.setRollbackOnly();
        });
        assertThat(audited("admin.grant", user.id())).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.admins where user_id = ?", Integer.class, user.id()))
                .isZero();
    }
}
