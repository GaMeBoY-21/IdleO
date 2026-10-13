package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin sessions (ADR 0027): a short random token, given once a second factor is checked, kept only as its SHA-256
 * hash. It works only with an ID token of the same sign-in identity (issuer and subject) that made it, so a token
 * on its own, or with someone else's sign-in, opens nothing. It lasts {@code session-ttl} from its last use, renewed
 * at most once a minute so reads aren't writes, and never past {@code session-max} from when it began.
 */
@Component
@ConditionalOnWebApplication
class AdminSessions {

    /** The request header that carries the token, beside the ID token in {@code Authorization}. */
    static final String HEADER = "Admin-Session";

    static final Duration RENEW_AFTER = Duration.ofMinutes(1);
    private static final int TOKEN_BYTES = 32;

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration ttl;
    private final Duration max;
    private final SecureRandom random = new SecureRandom();

    AdminSessions(JdbcClient jdbc, IdGenerator ids, Clock clock, AdminProperties properties) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.ttl = properties.sessionTtl();
        this.max = properties.sessionMax();
    }

    /** A new session's token, shown to the admin's browser once, and when it lapses unless used. */
    record Issued(String token, Instant expiresAt) {}

    /** Starts a session for this admin and sign-in identity, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    Issued start(UUID userId, UUID identityId) {
        var bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var now = now();
        var expiresAt = now.plus(ttl);
        jdbc.sql("""
                        insert into identity.admin_sessions
                            (id, user_id, identity_id, token_hash, created_at, expires_at, ends_by)
                        values (:id, :userId, :identityId, :hash, :now, :expiresAt, :endsBy)
                        """)
                .param("id", ids.newId())
                .param("userId", userId)
                .param("identityId", identityId)
                .param("hash", hash(token))
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("endsBy", Timestamp.from(now.plus(max)))
                .update();
        return new Issued(token, expiresAt);
    }

    /**
     * Uses the session: when the token is this admin's and identity's and hasn't lapsed, renews it and returns when
     * it now lapses.
     */
    Optional<Instant> use(UUID userId, UUID identityId, String token) {
        var now = now();
        return live(userId, identityId, token, now).map(session -> {
            var renewed = earlier(now.plus(ttl), session.endsBy());
            if (!renewed.isAfter(session.expiresAt().plus(RENEW_AFTER))) {
                return session.expiresAt();
            }
            jdbc.sql("update identity.admin_sessions set expires_at = :renewed where id = :id and ended_at is null")
                    .param("renewed", Timestamp.from(renewed))
                    .param("id", session.id())
                    .update();
            return renewed;
        });
    }

    /** When the session lapses, without renewing it; empty when it isn't live. */
    Optional<Instant> expiry(UUID userId, UUID identityId, String token) {
        return live(userId, identityId, token, now()).map(Live::expiresAt);
    }

    /** Ends the session, if it's this admin's. */
    void end(UUID userId, String token) {
        jdbc.sql("""
                        update identity.admin_sessions set ended_at = :now
                        where token_hash = :hash and user_id = :userId and ended_at is null
                        """)
                .param("now", Timestamp.from(now()))
                .param("hash", hash(token))
                .param("userId", userId)
                .update();
    }

    private record Live(UUID id, Instant expiresAt, Instant endsBy) {}

    private Optional<Live> live(UUID userId, UUID identityId, String token, Instant now) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select id, expires_at, ends_by from identity.admin_sessions
                        where token_hash = :hash and user_id = :userId and identity_id = :identityId
                          and ended_at is null and expires_at > :now
                        """)
                .param("hash", hash(token.strip()))
                .param("userId", userId)
                .param("identityId", identityId)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new Live(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("ends_by").toInstant()))
                .optional();
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
