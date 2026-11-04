package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Giving an admin a role or taking one away (ADR 0051), once another admin has approved. It's audited, moves the admin's
 * version, and ends their admin sessions, so their next one starts with what they may do now. The last Owner can't lose
 * the role.
 */
@Component
class AdminRoleChanges {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;
    private final Clock clock;

    AdminRoleChanges(JdbcClient jdbc, TransactionTemplate transactions, AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    void grant(UUID userId, String role, String reason, AuditTrail.Actor actor) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            insert into identity.admin_role_grants (user_id, role_id, granted_by, granted_at)
                            values (:userId, :role, :by, :now)
                            on conflict (user_id, role_id) do nothing
                            """)
                    .param("userId", userId)
                    .param("role", role)
                    .param("by", actor.label())
                    .param("now", now())
                    .update();
            changed(userId, "admin.role_grant", role, reason, actor);
        });
    }

    void remove(UUID userId, String role, String reason, AuditTrail.Actor actor) {
        transactions.executeWithoutResult(status -> {
            if ("owner".equals(role)) {
                // Every Owner's row, locked, so two removals at once can't leave none.
                var owners = jdbc.sql("""
                                select user_id from identity.admin_role_grants where role_id = 'owner' for update
                                """).query(UUID.class).list();
                if (owners.size() == 1 && owners.contains(userId)) {
                    throw ApiProblem.of(
                            Problem.ACTION_REFUSED, "This is the last Owner, so the role can't be taken away.");
                }
            }
            jdbc.sql("delete from identity.admin_role_grants where user_id = :userId and role_id = :role")
                    .param("userId", userId)
                    .param("role", role)
                    .update();
            changed(userId, "admin.role_remove", role, reason, actor);
        });
    }

    private void changed(UUID userId, String action, String role, String reason, AuditTrail.Actor actor) {
        jdbc.sql("update identity.admins set version = version + 1 where user_id = :userId")
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        update identity.admin_sessions set ended_at = :now
                        where user_id = :userId and ended_at is null
                        """).param("now", now()).param("userId", userId).update();
        audit.record(new AuditTrail.Entry(
                actor, action, "user", userId.toString(), reason, null, Map.of("role", Objects.requireNonNull(role))));
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
