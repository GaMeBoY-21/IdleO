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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who is an admin (ADR 0027). Only the {@code grant-admin} and {@code revoke-admin} operator commands change it;
 * there's no API for it. Revoking deletes the admin's second factor, recovery codes and sessions with the role, so
 * granting again starts with a fresh enrolment, and is the only way out of a lockout.
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

    /** Makes the active user with this verified email an admin. */
    Change grant(String email, String reason, AuditTrail.Actor actor) {
        requireReason(reason);
        return Objects.requireNonNull(transactions.execute(status -> {
            var userId = userWithEmail(email);
            int inserted = jdbc.sql("""
                            insert into identity.admins (user_id, granted_by, granted_at) values (:userId, :by, :now)
                            on conflict (user_id) do nothing
                            """)
                    .param("userId", userId)
                    .param("by", actor.label())
                    .param("now", Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)))
                    .update();
            if (inserted == 0) {
                return new Change(userId, email, false);
            }
            audit.record(new AuditTrail.Entry(
                    actor,
                    "admin.grant",
                    "user",
                    userId.toString(),
                    reason,
                    Map.of("admin", false),
                    Map.of("admin", true)));
            return new Change(userId, email, true);
        }));
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
