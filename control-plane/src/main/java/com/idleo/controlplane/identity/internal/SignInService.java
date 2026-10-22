package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.SecurityLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds the user behind a verified (issuer, subject), creating the user on first sign-in.
 *
 * <ul>
 *   <li>Two first requests at once still make one user. The unique (issuer, subject) constraint
 *       decides; the loser's transaction rolls back and it re-reads the winner's row.
 *   <li>A new account needs a verified email.
 *   <li>A new (issuer, subject) is never linked to an existing user by email; that's how accounts get
 *       taken over. Linking happens only in the provider move's controlled migration (ADR 0009).
 *   <li>While access is by invitation ({@code identity.invite-only}) a new user is {@code waiting}, until an
 *       invite code activates them.
 *   <li>{@code last_sign_in_at} is written at most once a minute per identity, so requests aren't
 *       writes.
 *   <li>A token whose {@code auth_time} is newer than any before it for this identity is a new sign-in: it goes in
 *       the security log (ADR 0037), once.
 *   <li>The provider's verified email is the account's: when a token brings a different one, the account takes it
 *       and the change goes in the security log.
 * </ul>
 */
@Service
class SignInService {

    static final Duration SIGN_IN_TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final IdentityProperties properties;
    private final SecurityLog security;

    SignInService(
            JdbcClient jdbc,
            UserRepository users,
            TransactionTemplate transactions,
            IdGenerator ids,
            Clock clock,
            IdentityProperties properties,
            SecurityLog security) {
        this.properties = properties;
        this.security = security;
        this.jdbc = jdbc;
        this.users = users;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
    }

    SignInOutcome signIn(SignInClaims claims) {
        var existing = find(claims);
        if (existing.isPresent()) {
            touch(existing.get());
            signedIn(existing.get(), claims);
            followEmail(existing.get(), claims);
            return outcome(existing.get());
        }
        if (!claims.emailVerified()) {
            return new SignInOutcome.EmailNotVerified();
        }
        var created = create(claims);
        if (created.isPresent()) {
            var identity = created.get();
            return properties.inviteOnly()
                    ? new SignInOutcome.Waiting(identity.userId(), identity.identityId())
                    : new SignInOutcome.Active(identity.userId(), identity.identityId());
        }
        // Another request created this identity first; use theirs.
        return outcome(find(claims).orElseThrow(() -> new IllegalStateException("identity vanished after conflict")));
    }

    private record Created(UUID userId, UUID identityId) {}

    private Optional<Created> create(SignInClaims claims) {
        return transactions.execute(status -> {
            var now = Instant.now(clock);
            var userId = ids.newId();
            var identityId = ids.newId();
            var newStatus = properties.inviteOnly() ? UserStatus.WAITING : UserStatus.ACTIVE;
            users.save(User.newUser(userId, newStatus, claims.email(), claims.displayName(), now));
            int inserted = jdbc.sql("""
                            insert into identity.external_identities
                                (id, user_id, issuer, subject, created_at, last_sign_in_at, signed_in_at)
                            values (:id, :userId, :issuer, :subject, :now, :now, :signedInAt)
                            on conflict (issuer, subject) do nothing
                            """)
                    .param("id", identityId)
                    .param("userId", userId)
                    .param("issuer", claims.issuer())
                    .param("subject", claims.subject())
                    .param("now", java.sql.Timestamp.from(now))
                    .param("signedInAt", claims.authTime() == null ? null : java.sql.Timestamp.from(claims.authTime()))
                    .update();
            if (inserted == 0) {
                status.setRollbackOnly();
                return Optional.<Created>empty();
            }
            security.record(SecurityLog.Kind.SIGN_IN, userId, "first");
            return Optional.of(new Created(userId, identityId));
        });
    }

    private Optional<KnownIdentity> find(SignInClaims claims) {
        return jdbc.sql("""
                        select e.id, e.user_id, e.last_sign_in_at, e.signed_in_at, u.status, u.email
                        from identity.external_identities e
                        join identity.users u on u.id = e.user_id
                        where e.issuer = :issuer and e.subject = :subject
                        """)
                .param("issuer", claims.issuer())
                .param("subject", claims.subject())
                .query((rs, row) -> new KnownIdentity(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("last_sign_in_at").toInstant(),
                        instant(rs.getTimestamp("signed_in_at")),
                        UserStatus.fromDb(rs.getString("status")),
                        rs.getString("email")))
                .optional();
    }

    private static @Nullable Instant instant(java.sql.@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** A newer {@code auth_time} than this identity has had: recorded once, whichever request sees it first. */
    private void signedIn(KnownIdentity identity, SignInClaims claims) {
        var authTime = claims.authTime();
        if (authTime == null || (identity.signedInAt() != null && !authTime.isAfter(identity.signedInAt()))) {
            return;
        }
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            update identity.external_identities set signed_in_at = :authTime
                            where id = :id and (signed_in_at is null or signed_in_at < :authTime)
                            """)
                    .param("authTime", java.sql.Timestamp.from(authTime))
                    .param("id", identity.id())
                    .update();
            if (updated == 1) {
                security.record(SecurityLog.Kind.SIGN_IN, identity.userId());
            }
        });
    }

    /** The provider verified a different email for this identity: the account takes it. */
    private void followEmail(KnownIdentity identity, SignInClaims claims) {
        var email = claims.email();
        if (!claims.emailVerified()
                || email == null
                || email.isBlank()
                || identity.status() == UserStatus.ERASED
                || email.equalsIgnoreCase(identity.email())) {
            return;
        }
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            update identity.users
                            set email = :email, email_verified = true, updated_at = :now, version = version + 1
                            where id = :id and email is distinct from :email and status <> 'erased'
                            """)
                    .param("email", email)
                    .param("now", java.sql.Timestamp.from(Instant.now(clock)))
                    .param("id", identity.userId())
                    .update();
            if (updated == 1) {
                security.record(SecurityLog.Kind.EMAIL_CHANGED, identity.userId());
            }
        });
    }

    private void touch(KnownIdentity identity) {
        var now = Instant.now(clock);
        var cutoff = now.minus(SIGN_IN_TOUCH_INTERVAL);
        if (identity.lastSignInAt().isAfter(cutoff)) {
            return;
        }
        jdbc.sql("""
                        update identity.external_identities set last_sign_in_at = :now
                        where id = :id and last_sign_in_at <= :cutoff
                        """)
                .param("now", java.sql.Timestamp.from(now))
                .param("cutoff", java.sql.Timestamp.from(cutoff))
                .param("id", identity.id())
                .update();
    }

    private static SignInOutcome outcome(KnownIdentity identity) {
        return switch (identity.status()) {
            case WAITING -> new SignInOutcome.Waiting(identity.userId(), identity.id());
            case ACTIVE -> new SignInOutcome.Active(identity.userId(), identity.id());
            case SUSPENDED, BANNED -> new SignInOutcome.Held(identity.userId(), identity.id());
            case ERASED -> new SignInOutcome.Suspended(identity.userId());
        };
    }

    private record KnownIdentity(
            UUID id,
            UUID userId,
            Instant lastSignInAt,
            @Nullable Instant signedInAt,
            UserStatus status,
            @Nullable String email) {}
}
