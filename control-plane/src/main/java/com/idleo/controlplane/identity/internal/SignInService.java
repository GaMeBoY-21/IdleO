package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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

    SignInService(
            JdbcClient jdbc,
            UserRepository users,
            TransactionTemplate transactions,
            IdGenerator ids,
            Clock clock,
            IdentityProperties properties) {
        this.properties = properties;
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
                                (id, user_id, issuer, subject, created_at, last_sign_in_at)
                            values (:id, :userId, :issuer, :subject, :now, :now)
                            on conflict (issuer, subject) do nothing
                            """)
                    .param("id", identityId)
                    .param("userId", userId)
                    .param("issuer", claims.issuer())
                    .param("subject", claims.subject())
                    .param("now", java.sql.Timestamp.from(now))
                    .update();
            if (inserted == 0) {
                status.setRollbackOnly();
                return Optional.<Created>empty();
            }
            return Optional.of(new Created(userId, identityId));
        });
    }

    private Optional<KnownIdentity> find(SignInClaims claims) {
        return jdbc.sql("""
                        select e.id, e.user_id, e.last_sign_in_at, u.status
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
                        UserStatus.fromDb(rs.getString("status"))))
                .optional();
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
            case SUSPENDED -> new SignInOutcome.Suspended(identity.userId());
        };
    }

    private record KnownIdentity(UUID id, UUID userId, Instant lastSignInAt, UserStatus status) {}
}
