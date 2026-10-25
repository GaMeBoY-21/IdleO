package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.AccountHolds;
import com.idleo.controlplane.identity.AccountStandings;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
class UserSuspensions implements AccountHolds, AccountStandings {

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
    static final class Refused extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** Why an admin suspends: misconduct as a host holds the account's payouts; anything else doesn't. */
    enum Cause {
        HOST_MISCONDUCT("host_misconduct"),
        OTHER("other");

        private final String dbValue;

        Cause(String dbValue) {
            this.dbValue = dbValue;
        }

        String dbValue() {
            return dbValue;
        }
    }

    Change suspend(UUID userId, String reason, AuditTrail.Actor actor, Cause cause) {
        if (userId.equals(actor.userId())) {
            throw new Refused("An admin can't suspend their own account.");
        }
        return change(
                userId,
                Set.of(UserStatus.ACTIVE),
                UserStatus.SUSPENDED,
                "user.suspend",
                reason,
                actor,
                cause.dbValue());
    }

    @Override
    public boolean payoutsHeld(UUID userId) {
        return jdbc.sql("""
                        select exists (select 1 from identity.users
                                       where id = :id and status = 'suspended' and suspension_cause = 'host_misconduct')
                        """).param("id", userId).query(Boolean.class).single();
    }

    /** Reinstate on the Users page: a suspended account, never a banned one (that is the review item's to lift). */
    Change unsuspend(UUID userId, String reason, AuditTrail.Actor actor) {
        return change(userId, Set.of(UserStatus.SUSPENDED), UserStatus.ACTIVE, "user.unsuspend", reason, actor);
    }

    @Override
    public boolean hold(UUID userId, String reason, AuditTrail.Actor actor) {
        return change(userId, Set.of(UserStatus.ACTIVE), UserStatus.SUSPENDED, "user.suspend", reason, actor)
                .changed();
    }

    @Override
    public boolean ban(UUID userId, String reason, AuditTrail.Actor actor) {
        return change(
                        userId,
                        Set.of(UserStatus.ACTIVE, UserStatus.SUSPENDED),
                        UserStatus.BANNED,
                        "user.ban",
                        reason,
                        actor)
                .changed();
    }

    @Override
    public boolean lift(UUID userId, String reason, AuditTrail.Actor actor) {
        return change(
                        userId,
                        Set.of(UserStatus.SUSPENDED, UserStatus.BANNED),
                        UserStatus.ACTIVE,
                        "user.hold_lifted",
                        reason,
                        actor)
                .changed();
    }

    /** Empty when there's no such user. */
    private Change change(
            UUID userId, Set<UserStatus> from, UserStatus to, String action, String reason, AuditTrail.Actor actor) {
        return change(userId, from, to, action, reason, actor, null);
    }

    /** As above, recording {@code cause} when the account becomes suspended, and forgetting it when it stops being. */
    private Change change(
            UUID userId,
            Set<UserStatus> from,
            UserStatus to,
            String action,
            String reason,
            AuditTrail.Actor actor,
            @org.jspecify.annotations.Nullable String cause) {
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
            if (!from.contains(current)) {
                throw new Refused("The account is " + current.dbValue() + ", so it can't be "
                        + switch (to) {
                            case SUSPENDED -> "suspended";
                            case BANNED -> "banned";
                            default -> "reinstated";
                        }
                        + ".");
            }
            jdbc.sql("""
                            update identity.users
                            set status = :to, suspension_cause = :cause, updated_at = :now, version = version + 1
                            where id = :id
                            """)
                    .param("to", to.dbValue())
                    .param("cause", to == UserStatus.SUSPENDED ? cause : null)
                    .param("now", Timestamp.from(Instant.now(clock)))
                    .param("id", userId)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    action,
                    "user",
                    userId.toString(),
                    reason,
                    Map.of("status", current.dbValue()),
                    to == UserStatus.SUSPENDED && cause != null
                            ? Map.of("status", to.dbValue(), "cause", cause)
                            : Map.of("status", to.dbValue())));
            return new Change(userId, current.dbValue(), to.dbValue(), true);
        }));
    }
}
