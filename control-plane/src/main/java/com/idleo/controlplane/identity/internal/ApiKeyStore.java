package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.SecurityLog;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * API keys (ADR 0025): made, listed, revoked and checked. A key's secret exists only in the answer that creates it;
 * the table holds its hash. At most {@value #MAX_ACTIVE} keys a person aren't revoked, counted under the person's
 * row lock so two creates at once can't pass the limit. {@code last_used_at} is written at most once a minute, so
 * requests aren't writes.
 */
@Service
class ApiKeyStore {

    static final int MAX_ACTIVE = 20;
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    enum Scope {
        READ,
        FULL;

        String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Scope> fromValue(String value) {
            for (var scope : values()) {
                if (scope.dbValue().equals(value)) {
                    return Optional.of(scope);
                }
            }
            return Optional.empty();
        }
    }

    enum Via {
        WEB("web"),
        CLI_LOGIN("cli-login");

        final String dbValue;

        Via(String dbValue) {
            this.dbValue = dbValue;
        }
    }

    /** A key as the person sees it in a list: never its secret. */
    record Key(
            UUID id,
            String name,
            String displayPrefix,
            String scope,
            String createdVia,
            Instant createdAt,
            @Nullable Instant lastUsedAt) {}

    /** A new key, with the one copy of its secret there will ever be. */
    record Created(Key key, String secret) {}

    /** The key a request was made with, and its owner's status. */
    record Use(UUID keyId, UUID userId, Scope scope, UserStatus userStatus) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final SecurityLog security;
    private final SecureRandom random = new SecureRandom();

    ApiKeyStore(JdbcClient jdbc, TransactionTemplate transactions, IdGenerator ids, Clock clock, SecurityLog security) {
        this.security = security;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
    }

    /** A new key, or empty when the person already has {@value #MAX_ACTIVE} that aren't revoked. */
    Optional<Created> create(UUID userId, String name, Scope scope, Via via) {
        return transactions.execute(status -> {
            jdbc.sql("select id from identity.users where id = :id for update")
                    .param("id", userId)
                    .query(UUID.class)
                    .single();
            int active = jdbc.sql("select count(*) from identity.api_keys where user_id = :user and revoked_at is null")
                    .param("user", userId)
                    .query(Integer.class)
                    .single();
            if (active >= MAX_ACTIVE) {
                return Optional.<Created>empty();
            }
            var secret = ApiKeySecrets.generate(random);
            var key = new Key(
                    ids.newId(),
                    name,
                    ApiKeySecrets.displayPrefix(secret),
                    scope.dbValue(),
                    via.dbValue,
                    Instant.now(clock),
                    null);
            jdbc.sql("""
                            insert into identity.api_keys
                                (id, user_id, name, scope, key_hash, display_prefix, created_via, created_at)
                            values (:id, :user, :name, :scope, :hash, :prefix, :via, :now)
                            """)
                    .param("id", key.id())
                    .param("user", userId)
                    .param("name", name)
                    .param("scope", key.scope())
                    .param("hash", ApiKeySecrets.hash(secret))
                    .param("prefix", key.displayPrefix())
                    .param("via", key.createdVia())
                    .param("now", Timestamp.from(key.createdAt()))
                    .update();
            security.record(SecurityLog.Kind.API_KEY_CREATED, userId, via.dbValue);
            return Optional.of(new Created(key, secret));
        });
    }

    /** The person's keys that aren't revoked, newest first. */
    List<Key> list(UUID userId) {
        return jdbc.sql("""
                        select id, name, display_prefix, scope, created_via, created_at, last_used_at
                        from identity.api_keys where user_id = :user and revoked_at is null
                        order by created_at desc, id desc
                        """)
                .param("user", userId)
                .query((rs, row) -> new Key(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("display_prefix"),
                        rs.getString("scope"),
                        rs.getString("created_via"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null
                                ? null
                                : rs.getTimestamp("last_used_at").toInstant()))
                .list();
    }

    /** Revokes one of the person's keys; false when they have no such key. Revoking it again changes nothing. */
    boolean revoke(UUID userId, UUID keyId) {
        var now = Timestamp.from(Instant.now(clock));
        int revoked = jdbc.sql("""
                        update identity.api_keys set revoked_at = :now
                        where id = :id and user_id = :user and revoked_at is null
                        """)
                .param("now", now)
                .param("id", keyId)
                .param("user", userId)
                .update();
        if (revoked == 1) {
            security.record(SecurityLog.Kind.API_KEY_REVOKED, userId);
        }
        return jdbc.sql("select count(*) from identity.api_keys where id = :id and user_id = :user")
                        .param("id", keyId)
                        .param("user", userId)
                        .query(Integer.class)
                        .single()
                == 1;
    }

    /** The key behind a bearer value, if it's one that isn't revoked; marks it used, at most once a minute. */
    Optional<Use> use(String secret) {
        var found = jdbc.sql("""
                        select k.id, k.user_id, k.scope, k.last_used_at, u.status
                        from identity.api_keys k join identity.users u on u.id = k.user_id
                        where k.key_hash = :hash and k.revoked_at is null
                        """)
                .param("hash", ApiKeySecrets.hash(secret))
                .query((rs, row) -> new Found(
                        new Use(
                                rs.getObject("id", UUID.class),
                                rs.getObject("user_id", UUID.class),
                                Scope.fromValue(rs.getString("scope")).orElseThrow(),
                                UserStatus.fromDb(rs.getString("status"))),
                        rs.getTimestamp("last_used_at") == null
                                ? null
                                : rs.getTimestamp("last_used_at").toInstant()))
                .optional();
        found.ifPresent(this::touch);
        return found.map(Found::use);
    }

    private void touch(Found found) {
        var now = Instant.now(clock);
        var cutoff = now.minus(TOUCH_INTERVAL);
        if (found.lastUsedAt() != null && found.lastUsedAt().isAfter(cutoff)) {
            return;
        }
        jdbc.sql("""
                        update identity.api_keys set last_used_at = :now
                        where id = :id and (last_used_at is null or last_used_at <= :cutoff)
                        """)
                .param("now", Timestamp.from(now))
                .param("cutoff", Timestamp.from(cutoff))
                .param("id", found.use().keyId())
                .update();
    }

    private record Found(Use use, @Nullable Instant lastUsedAt) {}
}
