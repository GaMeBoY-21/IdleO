package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.PersonalDataMask;
import com.idleo.controlplane.identity.AdminNoticed;
import com.idleo.controlplane.identity.SecurityLog;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin sessions (ADR 0027): a short random token, given once a second factor is checked, kept only as its SHA-256
 * hash. It works only with an ID token of the same sign-in identity (issuer and subject) that made it, so a token
 * on its own, or with someone else's sign-in, opens nothing. It lasts {@code session-ttl} from its last use, renewed
 * at most once a minute so reads aren't writes, and never past {@code session-max} from when it began. Each keeps its
 * start, last use, address and browser, and when its last factor was given (ADR 0051).
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
    private final SecurityLog security;
    private final Duration ttl;
    private final Duration max;
    private final ApplicationEventPublisher events;
    private final AdminInsiderSignals insider;
    private final SecureRandom random = new SecureRandom();

    AdminSessions(
            JdbcClient jdbc,
            IdGenerator ids,
            Clock clock,
            AdminProperties properties,
            SecurityLog security,
            ApplicationEventPublisher events,
            AdminInsiderSignals insider) {
        this.events = events;
        this.insider = insider;
        this.security = security;
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
        var sessionId = ids.newId();
        tellOfANewPlace(userId, now);
        jdbc.sql("""
                        insert into identity.admin_sessions
                            (id, user_id, identity_id, token_hash, created_at, expires_at, ends_by, factor_at,
                             last_used_at, ip, user_agent)
                        values (:id, :userId, :identityId, :hash, :now, :expiresAt, :endsBy, :now, :now,
                                cast(:ip as inet), :userAgent)
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("identityId", identityId)
                .param("hash", hash(token))
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("endsBy", Timestamp.from(now.plus(max)))
                .param("ip", AdminRequestContext.address())
                .param("userAgent", AdminRequestContext.userAgent())
                .update();
        AdminRequestContext.adminSession(sessionId);
        security.record(SecurityLog.Kind.ADMIN_SESSION_STARTED, userId);
        return new Issued(token, expiresAt);
    }

    /**
     * Uses the session: when the token is this admin's and identity's and hasn't lapsed, renews it, notes it as this
     * request's admin session, and returns when it now lapses.
     */
    Optional<Instant> use(UUID userId, UUID identityId, String token) {
        var now = now();
        return live(userId, identityId, token, now).map(session -> {
            AdminRequestContext.adminSession(session.id());
            AdminRequestContext.factorAt(session.factorAt());
            var renewed = earlier(now.plus(ttl), session.endsBy());
            if (!renewed.isAfter(session.expiresAt().plus(RENEW_AFTER))) {
                return session.expiresAt();
            }
            jdbc.sql("""
                            update identity.admin_sessions set expires_at = :renewed, last_used_at = :now
                            where id = :id and ended_at is null
                            """)
                    .param("now", Timestamp.from(now))
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

    /** This request's admin session has just given a factor: it counts for the next {@code step-up-window}. */
    @Transactional(propagation = Propagation.MANDATORY)
    Instant factorGiven(UUID sessionId) {
        var now = now();
        jdbc.sql("update identity.admin_sessions set factor_at = :now where id = :id")
                .param("now", Timestamp.from(now))
                .param("id", sessionId)
                .update();
        return now;
    }

    /**
     * Tells the admin when a session starts from a browser or an address none of their sessions has used. Their very
     * first has nothing to compare with. It's announced in this transaction, so it's told exactly once if it commits.
     */
    private void tellOfANewPlace(UUID userId, Instant now) {
        var ip = AdminRequestContext.address();
        var agent = AdminRequestContext.userAgent();
        var seen = jdbc.sql("""
                        select count(*) as sessions,
                               count(*) filter (where ip = cast(:ip as inet)) as same_ip,
                               count(*) filter (where user_agent = :agent) as same_agent
                        from identity.admin_sessions where user_id = :userId
                        """)
                .param("ip", ip)
                .param("agent", agent)
                .param("userId", userId)
                .query((rs, n) -> new long[] {rs.getLong("sessions"), rs.getLong("same_ip"), rs.getLong("same_agent")})
                .single();
        if (seen[0] == 0 || (seen[1] > 0 && seen[2] > 0)) {
            return;
        }
        insider.newPlace();
        events.publishEvent(new AdminNoticed(
                userId,
                "admin-new-place:" + ids.newId(),
                "A new sign-in to the IdleO admin console",
                "Your admin account signed in from a new "
                        + (seen[1] == 0
                                ? "address (" + (ip == null ? "unknown" : PersonalDataMask.ip(ip)) + ")"
                                : "browser")
                        + " at " + now + ". If this wasn't you, sign in and end that session, or tell an Owner now.",
                "/admin/security/"));
    }

    /** One admin session, for its owner or an Owner to read: the address is masked on its way out. */
    record Record(
            UUID id,
            UUID userId,
            Instant startedAt,
            @Nullable Instant lastUsedAt,
            Instant expiresAt,
            Instant endsBy,
            @Nullable Instant endedAt,
            @Nullable String ip,
            @Nullable String userAgent) {}

    /** The admin's sessions still live, or everyone's when {@code userId} is null, newest first. */
    List<Record> live(@Nullable UUID userId) {
        return jdbc.sql("""
                        select id, user_id, created_at, last_used_at, expires_at, ends_by, ended_at, host(ip) as address,
                               user_agent
                        from identity.admin_sessions
                        where ended_at is null and expires_at > :now
                          and (cast(:userId as uuid) is null or user_id = cast(:userId as uuid))
                        order by created_at desc
                        limit 200
                        """)
                .param("now", Timestamp.from(now()))
                .param("userId", userId)
                .query((rs, n) -> new Record(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null
                                ? null
                                : rs.getTimestamp("last_used_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("ends_by").toInstant(),
                        null,
                        rs.getString("address"),
                        rs.getString("user_agent")))
                .list();
    }

    /** Ends one session, if it's still live and {@code ownerId} is null or holds it. Whether it ended it. */
    boolean endById(UUID sessionId, @Nullable UUID ownerId, UUID endedBy) {
        return jdbc.sql("""
                                update identity.admin_sessions set ended_at = :now, ended_by = :endedBy
                                where id = :id and ended_at is null
                                  and (cast(:ownerId as uuid) is null or user_id = cast(:ownerId as uuid))
                                """)
                        .param("now", Timestamp.from(now()))
                        .param("endedBy", endedBy)
                        .param("id", sessionId)
                        .param("ownerId", ownerId)
                        .update()
                == 1;
    }

    /** Ends every session of this admin: their roles have changed. */
    void endAll(UUID userId, UUID endedBy) {
        jdbc.sql("""
                        update identity.admin_sessions set ended_at = :now, ended_by = :endedBy
                        where user_id = :userId and ended_at is null
                        """)
                .param("now", Timestamp.from(now()))
                .param("endedBy", endedBy)
                .param("userId", userId)
                .update();
    }

    private record Live(
            UUID id,
            Instant expiresAt,
            Instant endsBy,
            @Nullable Instant factorAt) {}

    private Optional<Live> live(UUID userId, UUID identityId, String token, Instant now) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select id, expires_at, ends_by, factor_at from identity.admin_sessions
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
                        rs.getTimestamp("ends_by").toInstant(),
                        rs.getTimestamp("factor_at") == null
                                ? null
                                : rs.getTimestamp("factor_at").toInstant()))
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
