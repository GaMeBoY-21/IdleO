package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Suspending an account from the admin console (ADR 0027): from the next request, its ID tokens and API keys are
 * refused with 403 {@code account-suspended}, and an admin among them loses the console too. Reinstating it makes it
 * active again. Only an active account is suspended: a waiting one can do nothing yet. Each change is audited.
 */
@Service
class UserSuspensions {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;
    private final Clock clock;

    UserSuspensions(JdbcClient jdbc, TransactionTemplate transactions, AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /** The outcome: {@code changed} is false when the account already had that status. */
    record Change(UUID userId, String from, String to, boolean changed) {}

    /** Refused; nothing changed. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    Change suspend(UUID userId, String reason, AuditTrail.Actor actor) {
        if (userId.equals(actor.userId())) {
            throw new Refused("An admin can't suspend their own account.");
        }
        return change(userId, UserStatus.ACTIVE, UserStatus.SUSPENDED, "user.suspend", reason, actor);
    }

    Change unsuspend(UUID userId, String reason, AuditTrail.Actor actor) {
        return change(userId, UserStatus.SUSPENDED, UserStatus.ACTIVE, "user.unsuspend", reason, actor);
    }

    /** Empty when there's no such user. */
    private Change change(
            UUID userId, UserStatus from, UserStatus to, String action, String reason, AuditTrail.Actor actor) {
        if (reason == null || reason.isBlank()) {
            throw new Refused("A reason is required.");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            var current = jdbc.sql("select status from identity.users where id = :id for update")
                    .param("id", userId)
                    .query(String.class)
                    .optional()
                    .map(UserStatus::fromDb)
                    .orElseThrow(() -> new Refused("There's no user " + userId + "."));
            if (current == to) {
                return new Change(userId, current.dbValue(), to.dbValue(), false);
            }
            if (current != from) {
                throw new Refused("The account is " + current.dbValue() + ", so it can't be "
                        + (to == UserStatus.SUSPENDED ? "suspended" : "reinstated") + ".");
            }
            jdbc.sql("update identity.users set status = :to, updated_at = :now, version = version + 1 where id = :id")
                    .param("to", to.dbValue())
                    .param("now", Timestamp.from(Instant.now(clock)))
                    .param("id", userId)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    action,
                    "user",
                    userId.toString(),
                    reason,
                    Map.of("status", from.dbValue()),
                    Map.of("status", to.dbValue())));
            return new Change(userId, from.dbValue(), to.dbValue(), true);
        }));
    }
}
