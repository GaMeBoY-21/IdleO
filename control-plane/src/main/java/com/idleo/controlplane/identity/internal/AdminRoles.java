package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who is an admin (ADR 0027). Only the {@code grant-admin} and {@code revoke-admin} operator commands change it;
 * there's no API for it. Revoking deletes the admin's second factor, recovery codes, roles and sessions with it, so
 * granting again starts with a fresh enrolment, and is the only way out of a lockout. {@code grant-admin} can also
 * give the admin a role, which is how the first Owner exists (ADR 0051).
 */
@Service
class AdminRoles {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;
    private final Clock clock;

    AdminRoles(JdbcClient jdbc, TransactionTemplate transactions, AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /** The outcome: {@code changed} is false when the user already had (or already lacked) the role. */
    record Change(UUID userId, String email, boolean changed) {}

    /** Refused; nothing changed. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** Makes the active user with this verified email an admin, holding {@code role} when one is named. */
    Change grant(String email, String reason, AuditTrail.Actor actor, @Nullable String role) {
        requireReason(reason);
        return Objects.requireNonNull(transactions.execute(status -> {
            var userId = userWithEmail(email);
            if (role != null && !roleExists(role)) {
                throw new Refused("there's no role " + role);
            }
            int inserted = jdbc.sql("""
                            insert into identity.admins (user_id, granted_by, granted_at) values (:userId, :by, :now)
                            on conflict (user_id) do nothing
                            """)
                    .param("userId", userId)
                    .param("by", actor.label())
                    .param("now", Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)))
                    .update();
            boolean given = role != null && give(userId, role, actor);
            if (inserted == 0 && !given) {
                return new Change(userId, email, false);
            }
            var after = new LinkedHashMap<String, Object>();
            after.put("admin", true);
            if (given) {
                after.put("role", role);
                // Changing an admin's roles ends their sessions, however it's done (ADR 0051).
                jdbc.sql("""
                                update identity.admin_sessions set ended_at = :now
                                where user_id = :userId and ended_at is null
                                """)
                        .param("now", Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)))
                        .param("userId", userId)
                        .update();
                jdbc.sql("update identity.admins set version = version + 1 where user_id = :userId")
                        .param("userId", userId)
                        .update();
            }
            audit.record(new AuditTrail.Entry(
                    actor, "admin.grant", "user", userId.toString(), reason, Map.of("admin", inserted == 0), after));
            return new Change(userId, email, true);
        }));
    }

    private boolean roleExists(String role) {
        return jdbc.sql("select count(*) from identity.admin_roles where id = :role")
                        .param("role", role)
                        .query(Integer.class)
                        .single()
                == 1;
    }

    private boolean give(UUID userId, String role, AuditTrail.Actor actor) {
        return jdbc.sql("""
                                insert into identity.admin_role_grants (user_id, role_id, granted_by, granted_at)
                                values (:userId, :role, :by, :now)
                                on conflict (user_id, role_id) do nothing
                                """)
                        .param("userId", userId)
                        .param("role", role)
                        .param("by", actor.label())
                        .param("now", Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)))
                        .update()
                == 1;
    }

    /** Takes the role away, with the second factor, the recovery codes and every admin session. */
    Change revoke(String email, String reason, AuditTrail.Actor actor) {
        requireReason(reason);
        return Objects.requireNonNull(transactions.execute(status -> {
            var userId = userWithEmail(email);
            var before = jdbc.sql("""
                            select totp_confirmed_at is not null as enrolled, locked_at is not null as locked
                            from identity.admins where user_id = :userId for update
                            """)
                    .param("userId", userId)
                    .query((rs, n) -> {
                        var state = new LinkedHashMap<String, Object>();
                        state.put("admin", true);
                        state.put("enrolled", rs.getBoolean("enrolled"));
                        state.put("locked", rs.getBoolean("locked"));
                        return state;
                    })
                    .optional();
            if (before.isEmpty()) {
                return new Change(userId, email, false);
            }
            jdbc.sql("delete from identity.admins where user_id = :userId")
                    .param("userId", userId)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor, "admin.revoke", "user", userId.toString(), reason, before.get(), Map.of("admin", false)));
            return new Change(userId, email, true);
        }));
    }

    private UUID userWithEmail(String email) {
        var matches = jdbc.sql("""
                        select id, status from identity.users
                        where lower(email) = lower(:email) and email_verified
                        order by created_at
                        """)
                .param("email", email.strip())
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("status")))
                .list();
        if (matches.isEmpty()) {
            throw new Refused("no one with the verified email " + email + " has signed in yet");
        }
        if (matches.size() > 1) {
            throw new Refused("more than one account has the email " + email + ": "
                    + matches.stream().map(match -> match.getKey().toString()).toList());
        }
        var match = matches.getFirst();
        if (!UserStatus.ACTIVE.dbValue().equals(match.getValue())) {
            throw new Refused("the account " + match.getKey() + " is " + match.getValue() + ", not active");
        }
        return match.getKey();
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new Refused("a reason is required");
        }
    }
}
