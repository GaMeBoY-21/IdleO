package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Device sign-in for the CLI (ADR 0025), shaped like RFC 8628.
 *
 * <ol>
 *   <li>The CLI starts a login: a user code to compare (like {@code WDJB-MJHT}), a link to the web app's approval
 *       page, and a poll token only the CLI holds. Both secrets are stored as hashes.
 *   <li>A person signed in to the web app finds it by its code and approves it.
 *   <li>The CLI's poll, with its token, then gets a new full API key, exactly once: the key is made and the login
 *       spent in one transaction, under the login's row lock.
 * </ol>
 *
 * A login lasts {@code identity.cli-login.ttl}; after that, or once spent, it's {@code cli-login-expired}.
 */
@Service
class CliLogins {

    /** RFC 8628 section 6.1's alphabet: consonants only, so a code never spells a word. 8 of them, about 34 bits. */
    private static final String ALPHABET = "BCDFGHJKLMNPQRSTVWXZ";

    private static final int CODE_LENGTH = 8;

    record Started(UUID id, String userCode, String pollToken, Instant expiresAt) {}

    record Pending(UUID id, String deviceName, Instant expiresAt) {}

    sealed interface Approval {
        record Approved(Pending login) implements Approval {}

        record Expired() implements Approval {}

        record NotFound() implements Approval {}
    }

    sealed interface Redemption {
        record Issued(ApiKeyStore.Created key) implements Redemption {}

        record WaitingForApproval() implements Redemption {}

        record Expired() implements Redemption {}

        record NotFound() implements Redemption {}

        record TooManyKeys() implements Redemption {}
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ApiKeyStore keys;
    private final IdGenerator ids;
    private final IdentityProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    CliLogins(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            ApiKeyStore keys,
            IdGenerator ids,
            IdentityProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.keys = keys;
        this.ids = ids;
        this.properties = properties;
        this.clock = clock;
    }

    Started start(String deviceName) {
        var now = Instant.now(clock);
        var expiresAt = now.plus(properties.cliLogin().ttl());
        var tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        var pollToken = ApiKeySecrets.base32(tokenBytes);
        var id = ids.newId();
        while (true) {
            var code = userCode();
            int inserted = jdbc.sql("""
                            insert into identity.cli_logins
                                (id, user_code_hash, poll_token_hash, device_name, created_at, expires_at)
                            values (:id, :code, :token, :device, :now, :expiresAt)
                            on conflict (user_code_hash) do nothing
                            """)
                    .param("id", id)
                    .param("code", hashCode(code))
                    .param("token", ApiKeySecrets.sha256(pollToken))
                    .param("device", deviceName)
                    .param("now", Timestamp.from(now))
                    .param("expiresAt", Timestamp.from(expiresAt))
                    .update();
            if (inserted == 1) {
                return new Started(id, code, pollToken, expiresAt);
            }
        }
    }

    /** The login this code names, while it can still be approved. */
    Optional<Pending> findPending(String userCode) {
        return jdbc.sql("""
                        select id, device_name, expires_at from identity.cli_logins
                        where user_code_hash = :code and approved_at is null and expires_at > :now
                        """)
                .param("code", hashCode(userCode))
                .param("now", Timestamp.from(Instant.now(clock)))
                .query((rs, row) -> new Pending(
                        rs.getObject("id", UUID.class),
                        rs.getString("device_name"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    /** Approves a login for this person. Approving one they already approved changes nothing. */
    Approval approve(UUID id, UUID userId) {
        return transactions.execute(status -> {
            var row = lock(id);
            if (row.isEmpty()) {
                return new Approval.NotFound();
            }
            var login = row.get();
            var now = Instant.now(clock);
            if (login.approvedBy() != null) {
                return login.approvedBy().equals(userId)
                                && login.redeemedAt() == null
                                && login.expiresAt().isAfter(now)
                        ? new Approval.Approved(login.pending())
                        : new Approval.Expired();
            }
            if (!login.expiresAt().isAfter(now)) {
                return new Approval.Expired();
            }
            jdbc.sql("update identity.cli_logins set approved_by = :user, approved_at = :now where id = :id")
                    .param("user", userId)
                    .param("now", Timestamp.from(now))
                    .param("id", id)
                    .update();
            return new Approval.Approved(login.pending());
        });
    }

    /** The CLI's poll: its key once approved, exactly once. A wrong poll token is as if there were no login. */
    Redemption redeem(UUID id, String pollToken) {
        return transactions.execute(status -> {
            var row = lock(id);
            if (row.isEmpty() || !MessageDigest.isEqual(row.get().pollTokenHash(), ApiKeySecrets.sha256(pollToken))) {
                return new Redemption.NotFound();
            }
            var login = row.get();
            if (login.redeemedAt() != null || !login.expiresAt().isAfter(Instant.now(clock))) {
                return new Redemption.Expired();
            }
            if (login.approvedBy() == null) {
                return new Redemption.WaitingForApproval();
            }
            var created = keys.create(
                    login.approvedBy(),
                    "CLI on " + login.deviceName(),
                    ApiKeyStore.Scope.FULL,
                    ApiKeyStore.Via.CLI_LOGIN);
            if (created.isEmpty()) {
                return new Redemption.TooManyKeys();
            }
            jdbc.sql("update identity.cli_logins set redeemed_at = :now, api_key_id = :key where id = :id")
                    .param("now", Timestamp.from(Instant.now(clock)))
                    .param("key", created.get().key().id())
                    .param("id", id)
                    .update();
            return new Redemption.Issued(created.get());
        });
    }

    /** What a person types or the link carries, compared as capitals without the dash. */
    static String normalise(String userCode) {
        return userCode.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    private static byte[] hashCode(String userCode) {
        return ApiKeySecrets.sha256(normalise(userCode));
    }

    private String userCode() {
        var code = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i == CODE_LENGTH / 2) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private Optional<Row> lock(UUID id) {
        return jdbc.sql("""
                        select id, poll_token_hash, device_name, expires_at, approved_by, redeemed_at
                        from identity.cli_logins where id = :id for update
                        """)
                .param("id", id)
                .query((rs, row) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getBytes("poll_token_hash"),
                        rs.getString("device_name"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("approved_by", UUID.class),
                        rs.getTimestamp("redeemed_at") == null
                                ? null
                                : rs.getTimestamp("redeemed_at").toInstant()))
                .optional();
    }

    private record Row(
            UUID id,
            byte[] pollTokenHash,
            String deviceName,
            Instant expiresAt,
            @Nullable UUID approvedBy,
            @Nullable Instant redeemedAt) {

        Pending pending() {
            return new Pending(id, deviceName, expiresAt);
        }
    }
}
