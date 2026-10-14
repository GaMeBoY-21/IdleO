package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An admin's second factor (ADR 0027): a TOTP secret (RFC 6238) set up at their first visit and confirmed with a
 * code from it, ten one-time recovery codes, and the check that starts an admin session.
 *
 * <ul>
 *   <li>The secret is shown once, when it's made; asking again before it's confirmed makes a new one. Once it's
 *       confirmed it's never shown again.
 *   <li>Every code tried is kept, right or wrong, under the admin's row lock. The {@value #MAX_WRONG}th wrong code
 *       in {@link #WINDOW} locks admin access until an operator revokes the role and grants it again; a locked admin
 *       is treated as no admin at all.
 *   <li>A TOTP code is accepted once: never again for its time step, nor an earlier one.
 * </ul>
 */
@Service
@ConditionalOnWebApplication
class AdminSecondFactor {

    static final int MAX_WRONG = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int RECOVERY_CODES = 10;

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final TotpSecrets secrets;
    private final CodeHashes hashes;
    private final AdminSessions sessions;
    private final AuditTrail audit;
    private final IdGenerator ids;
    private final Clock clock;
    private final String issuer;
    private final SecureRandom random = new SecureRandom();

    AdminSecondFactor(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            TotpSecrets secrets,
            AdminSessions sessions,
            AuditTrail audit,
            IdGenerator ids,
            Clock clock,
            AdminProperties properties,
            CodeHashes hashes) {
        this.hashes = hashes;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.secrets = secrets;
        this.sessions = sessions;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.issuer = properties.totpIssuer();
    }

    /** An admin whose access isn't locked, and whether their second factor is set up. */
    record Admin(UUID userId, boolean enrolled) {}

    /** A new, unconfirmed secret, for the admin's authenticator app: as text and as a QR code's link. */
    record Enrolment(String secret, String otpauthUri) {}

    sealed interface Outcome {
        /** The code was right: a new admin session, and the recovery codes when this confirmed the enrolment. */
        record Accepted(AdminSessions.Issued session, List<String> recoveryCodes) implements Outcome {}

        /** The code was wrong or used; {@code locked} when this was the last wrong code allowed. */
        record Wrong(boolean locked) implements Outcome {}

        /** Not possible now: no secret to confirm, already confirmed, or not yet confirmed. */
        record NotNow(Problem problem, String why) implements Outcome {}
    }

    /** The admin behind this user, unless they aren't one or are locked out. */
    Optional<Admin> admin(UUID userId) {
        return jdbc.sql("""
                        select totp_confirmed_at is not null as enrolled from identity.admins
                        where user_id = :userId and locked_at is null
                        """)
                .param("userId", userId)
                .query((rs, n) -> new Admin(userId, rs.getBoolean("enrolled")))
                .optional();
    }

    /** Makes a new secret to set up, replacing an unconfirmed one; empty once one is confirmed. */
    Optional<Enrolment> enrol(UUID userId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var row = lock(userId);
            if (row == null || row.confirmed()) {
                return Optional.<Enrolment>empty();
            }
            var secret = Totp.newSecret(random);
            jdbc.sql("update identity.admins set totp_secret = :sealed where user_id = :userId")
                    .param("sealed", secrets.seal(userId, secret))
                    .param("userId", userId)
                    .update();
            return Optional.of(new Enrolment(Totp.base32(secret), Totp.uri(issuer, row.account(), secret)));
        }));
    }

    /** Confirms the secret with a code from it: the second factor is set up, and an admin session starts. */
    Outcome confirm(UUID userId, UUID identityId, String code) {
        return check(
                userId,
                identityId,
                row -> {
                    if (row.confirmed()) {
                        return new Outcome.NotNow(Problem.SECOND_FACTOR_EXISTS, "The second factor is already set up.");
                    }
                    if (row.sealedSecret() == null) {
                        return new Outcome.NotNow(Problem.SECOND_FACTOR_MISSING, "Ask for a secret to set up first.");
                    }
                    return null;
                },
                row -> totp(row, code),
                true);
    }

    /** Checks a TOTP code, or one of the recovery codes, and starts an admin session. */
    Outcome signIn(UUID userId, UUID identityId, @Nullable String code, @Nullable String recoveryCode) {
        return check(
                userId,
                identityId,
                row -> row.confirmed()
                        ? null
                        : new Outcome.NotNow(Problem.SECOND_FACTOR_MISSING, "Set up the second factor first."),
                row -> recoveryCode != null ? recovery(userId, recoveryCode) : totp(row, code == null ? "" : code),
                false);
    }

    private interface Precondition {
        Outcome.@Nullable NotNow refuse(Row row);
    }

    private interface Checker {
        boolean accept(Row row);
    }

    private Outcome check(
            UUID userId, UUID identityId, Precondition precondition, Checker checker, boolean confirming) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var row = lock(userId);
            if (row == null) {
                return new Outcome.Wrong(true);
            }
            var refusal = precondition.refuse(row);
            if (refusal != null) {
                return refusal;
            }
            var now = now();
            boolean accepted = checker.accept(row);
            jdbc.sql("""
                            insert into identity.admin_code_attempts (id, user_id, succeeded, attempted_at)
                            values (:id, :userId, :succeeded, :now)
                            """)
                    .param("id", ids.newId())
                    .param("userId", userId)
                    .param("succeeded", accepted)
                    .param("now", Timestamp.from(now))
                    .update();
            if (!accepted) {
                return new Outcome.Wrong(lockIfTooMany(userId, now));
            }
            var codes = confirming ? confirmEnrolment(userId, now) : List.<String>of();
            return new Outcome.Accepted(sessions.start(userId, identityId), codes);
        }));
    }

    /** A TOTP code from the admin's secret, for a time step after the last one accepted. */
    private boolean totp(Row row, String code) {
        var sealed = Objects.requireNonNull(row.sealedSecret());
        OptionalLong step = Totp.verify(
                secrets.open(row.userId(), sealed),
                code,
                now(),
                row.lastStep() == null ? Long.MIN_VALUE : row.lastStep());
        if (step.isEmpty()) {
            return false;
        }
        jdbc.sql("update identity.admins set last_totp_step = :step where user_id = :userId")
                .param("step", step.getAsLong())
                .param("userId", row.userId())
                .update();
        return true;
    }

    /** One of the admin's unused recovery codes, used up by this. */
    private boolean recovery(UUID userId, String code) {
        return jdbc.sql("""
                        update identity.admin_recovery_codes set used_at = :now
                        where user_id = :userId and code_hash = :hash and used_at is null
                        """)
                        .param("now", Timestamp.from(now()))
                        .param("userId", userId)
                        .param("hash", hashes.hash(CodeHashes.Kind.RECOVERY, code))
                        .update()
                == 1;
    }

    private List<String> confirmEnrolment(UUID userId, Instant now) {
        jdbc.sql("update identity.admins set totp_confirmed_at = :now where user_id = :userId")
                .param("now", Timestamp.from(now))
                .param("userId", userId)
                .update();
        var codes = new ArrayList<String>();
        while (codes.size() < RECOVERY_CODES) {
            var code = recoveryCode();
            int inserted = jdbc.sql("""
                            insert into identity.admin_recovery_codes (id, user_id, code_hash, created_at)
                            values (:id, :userId, :hash, :now)
                            on conflict (code_hash) do nothing
                            """)
                    .param("id", ids.newId())
                    .param("userId", userId)
                    .param("hash", hashes.hash(CodeHashes.Kind.RECOVERY, code))
                    .param("now", Timestamp.from(now))
                    .update();
            if (inserted == 1) {
                codes.add(code);
            }
        }
        audit.record(new AuditTrail.Entry(
                audit.admin(new CurrentUser(userId)),
                "admin.second_factor_confirm",
                "user",
                userId.toString(),
                "set up their second factor",
                Map.of("enrolled", false),
                Map.of("enrolled", true, "recoveryCodes", RECOVERY_CODES)));
        return List.copyOf(codes);
    }

    /** Locks admin access at the {@value #MAX_WRONG}th wrong code in the window; true when it's now locked. */
    private boolean lockIfTooMany(UUID userId, Instant now) {
        int wrong = jdbc.sql("""
                        select count(*) from identity.admin_code_attempts
                        where user_id = :userId and not succeeded and attempted_at > :since
                        """)
                .param("userId", userId)
                .param("since", Timestamp.from(now.minus(WINDOW)))
                .query(Integer.class)
                .single();
        if (wrong < MAX_WRONG) {
            return false;
        }
        jdbc.sql("update identity.admins set locked_at = :now where user_id = :userId and locked_at is null")
                .param("now", Timestamp.from(now))
                .param("userId", userId)
                .update();
        jdbc.sql("update identity.admin_sessions set ended_at = :now where user_id = :userId and ended_at is null")
                .param("now", Timestamp.from(now))
                .param("userId", userId)
                .update();
        audit.record(new AuditTrail.Entry(
                AuditTrail.Actor.system("second factor lockout"),
                "admin.lock",
                "user",
                userId.toString(),
                MAX_WRONG + " wrong codes in " + WINDOW.toMinutes() + " minutes",
                Map.of("locked", false),
                Map.of("locked", true)));
        return true;
    }

    /** Ten characters from the invite codes' alphabet, in two groups: about 50 bits. */
    private String recoveryCode() {
        var chars = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            chars.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return chars.substring(0, 5) + "-" + chars.substring(5);
    }

    private record Row(
            UUID userId,
            String account,
            byte @Nullable [] sealedSecret,
            boolean confirmed,
            @Nullable Long lastStep) {}

    /** The admin's row, locked, unless they aren't an admin or are locked out. */
    private @Nullable Row lock(UUID userId) {
        return jdbc.sql("""
                        select a.totp_secret, a.totp_confirmed_at is not null as confirmed, a.last_totp_step,
                               coalesce(u.email, u.id::text) as account
                        from identity.admins a join identity.users u on u.id = a.user_id
                        where a.user_id = :userId and a.locked_at is null
                        for update of a
                        """)
                .param("userId", userId)
                .query((rs, n) -> new Row(
                        userId,
                        rs.getString("account"),
                        rs.getBytes("totp_secret"),
                        rs.getBoolean("confirmed"),
                        rs.getObject("last_totp_step", Long.class)))
                .optional()
                .orElse(null);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
