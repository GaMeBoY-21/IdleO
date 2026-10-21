package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.ContactDetails;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** The audit log (ADR 0027) is append-only, in the database's own rules and in its grants. */
@ControlPlaneTest
class AuditLogTests {

    @Autowired
    AuditTrail audit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    private UUID recordOne() {
        var target = UUID.randomUUID();
        transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                AuditTrail.Actor.operator("nikki"),
                "host.activate",
                "host",
                target.toString(),
                "passed its checks",
                Map.of("status", "pending"),
                Map.of("status", "active"))));
        return target;
    }

    @Test
    void keepsWhoWhatTargetReasonAndTheChangeAsJson() {
        var target = recordOne();
        var row = jdbc.queryForMap(
                "select actor_kind, actor_user_id, actor_name, action, reason, before->>'status' as before_status,"
                        + " after->>'status' as after_status from identity.audit_log where target_id = ?",
                target.toString());
        assertThat(row)
                .containsEntry("actor_kind", "operator")
                .containsEntry("actor_name", "nikki")
                .containsEntry("action", "host.activate")
                .containsEntry("reason", "passed its checks")
                .containsEntry("before_status", "pending")
                .containsEntry("after_status", "active");
        assertThat(row.get("actor_user_id")).isNull();
    }

    @Test
    void refusesEveryChangeAndDeletion() {
        var target = recordOne().toString();
        assertThatThrownBy(() ->
                        jdbc.update("update identity.audit_log set reason = 'edited' where target_id = ?", target))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from identity.audit_log where target_id = ?", target))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate identity.audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where target_id = ?", Integer.class, target))
                .isOne();
    }

    @Test
    void givesTheApplicationsDatabaseUserNoRightToUpdateDeleteOrTruncate() {
        var privileges = jdbc.queryForList("""
                select privilege_type from information_schema.role_table_grants
                where table_schema = 'identity' and table_name = 'audit_log' and grantee = current_user
                """, String.class);
        assertThat(privileges).contains("INSERT", "SELECT").doesNotContain("UPDATE", "DELETE", "TRUNCATE");
        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.role_table_grants where table_schema = 'identity'"
                                + " and table_name = 'audit_log' and grantee = 'PUBLIC'",
                        Integer.class))
                .isZero();
    }

    @Test
    void recordsOnlyInsideTheActionsTransactionAndOnlyWithAReason() {
        var entry =
                new AuditTrail.Entry(AuditTrail.Actor.system("tests"), "host.activate", "host", "h", "why", null, null);
        assertThatThrownBy(() -> audit.record(entry)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                        AuditTrail.Actor.system("tests"), "host.activate", "host", "h", " ", null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditTrail.Actor(AuditTrail.Actor.Kind.ADMIN, null, "someone"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnEntryWithAnEmailOrAnIpAddressAnywhereInIt() {
        var target = UUID.randomUUID().toString();
        for (var entry : List.of(
                new AuditTrail.Entry(
                        AuditTrail.Actor.operator("asha@example.com"),
                        "host.activate",
                        "host",
                        target,
                        "why",
                        null,
                        null),
                new AuditTrail.Entry(
                        AuditTrail.Actor.system("tests"),
                        "host.activate",
                        "host",
                        target,
                        "asked by a@b.in",
                        null,
                        null),
                new AuditTrail.Entry(
                        AuditTrail.Actor.system("tests"),
                        "host.activate",
                        "host",
                        target,
                        "from 203.0.113.7",
                        null,
                        null),
                new AuditTrail.Entry(
                        AuditTrail.Actor.system("tests"),
                        "host.activate",
                        "host",
                        target,
                        "why",
                        null,
                        Map.of("address", "2001:db8::1")))) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> audit.record(entry)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.audit_log where target_id = ?", Integer.class, target))
                .isZero();
    }

    @Test
    void namesAnAdminByTheirIdAndNeverByTheirEmail() {
        var actor = audit.admin(new CurrentUser(UUID.fromString("01900000-0000-7000-8000-000000000001")));
        assertThat(actor.name()).isEqualTo("01900000-0000-7000-8000-000000000001");
        assertThat(actor.label()).isEqualTo("admin 01900000-0000-7000-8000-000000000001");
    }

    /**
     * Every row any test has written by now (they share one database): none holds an email, an IP address, or any
     * account's email or name, in any column.
     */
    @Test
    void holdsNoRowNamingAPerson() {
        recordOne();
        var people = jdbc.queryForList("""
                select lower(value) from (
                    select email as value from identity.users
                    union select display_name from identity.users
                ) known where value is not null and length(value) >= 4
                """, String.class);
        var rows = jdbc.queryForList("""
                select concat_ws(' ', actor_name, target_id, reason, before::text, after::text)
                from identity.audit_log
                """, String.class);
        assertThat(rows).isNotEmpty();
        for (var row : rows) {
            assertThat(ContactDetails.found(row)).as(row).isFalse();
            var text = row.toLowerCase(Locale.ROOT);
            assertThat(people).as(row).noneMatch(text::contains);
        }
    }
}
