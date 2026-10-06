package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.sessions.AttemptLost;
import com.idleo.controlplane.sessions.SessionEnded;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session rows and the one way their state changes: {@link #transition}, which checks the table of allowed
 * moves, bumps the version and writes a {@code session_events} row in the same transaction.
 */
@Component
class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    SessionStore(JdbcClient jdbc, IdGenerator ids, Clock clock, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.events = events;
    }

    record Session(
            UUID id,
            UUID renterId,
            @Nullable UUID hostId,
            String type,
            SessionState state,
            int attempt,
            String templateId,
            String imageDigest,
            int vramNeededMb,
            int maxPricePaisePerMin,
            @Nullable Integer pricePaisePerMin,
            long maxSpendPaise,
            int maxRuntimeSeconds,
            String idempotencyKey,
            String requestHash,
            Instant stateChangedAt,
            int version,
            Instant createdAt,
            @Nullable Instant startedAt,
            @Nullable String errorCode) {}

    record Event(@Nullable String fromState, String toState, String reason, Instant at) {}

    enum Outcome {
        APPLIED,
        /** The move isn't in the table of allowed transitions. */
        REFUSED,
        /** The session changed since the caller read it. */
        STALE,
        MISSING
    }

    /** What a transition does to the host columns besides the state. */
    sealed interface HostChange {
        record Keep() implements HostChange {}

        record Assign(UUID hostId, int pricePaisePerMin) implements HostChange {}

        record Clear() implements HostChange {}
    }

    static final HostChange KEEP = new HostChange.Keep();
    static final HostChange CLEAR = new HostChange.Clear();

    Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** Inserts a new REQUESTED session and its first event; false if the renter already used the key. */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean insert(Session session) {
        int inserted = jdbc.sql("""
                        insert into sessions.sessions
                            (id, renter_id, host_id, type, state, attempt, template_id, image_digest, vram_needed_mb,
                             max_price_paise_per_min, price_paise_per_min, max_spend_paise, max_runtime_seconds,
                             idempotency_key, request_hash, state_changed_at, version, created_at)
                        values (:id, :renterId, null, :type, :state, 1, :templateId, :image, :vram,
                                :maxPrice, null, :maxSpend, :maxRuntime, :key, :hash, :now, 0, :now)
                        on conflict (renter_id, idempotency_key) do nothing
                        """)
                .param("id", session.id())
                .param("renterId", session.renterId())
                .param("type", session.type())
                .param("state", session.state().name())
                .param("templateId", session.templateId())
                .param("image", session.imageDigest())
                .param("vram", session.vramNeededMb())
                .param("maxPrice", session.maxPricePaisePerMin())
                .param("maxSpend", session.maxSpendPaise())
                .param("maxRuntime", session.maxRuntimeSeconds())
                .param("key", session.idempotencyKey())
                .param("hash", session.requestHash())
                .param("now", Timestamp.from(session.createdAt()))
                .update();
        if (inserted == 1) {
            event(session.id(), null, session.state(), "requested", session.createdAt());
        }
        return inserted == 1;
    }

    /**
     * Applies {@code event} to the session if the table allows it from its current state, and, when
     * {@code expectedVersion} is given, only if the session is still at that version.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Outcome transition(
            UUID sessionId,
            SessionEvent event,
            String reason,
            @Nullable Integer expectedVersion,
            HostChange hostChange) {
        var current = jdbc.sql(
                        "select state, version, attempt, started_at from sessions.sessions where id = :id for update")
                .param("id", sessionId)
                .query((rs, n) -> new Object[] {
                    SessionState.valueOf(rs.getString("state")),
                    rs.getInt("version"),
                    rs.getInt("attempt"),
                    rs.getTimestamp("started_at") != null
                })
                .optional();
        if (current.isEmpty()) {
            return Outcome.MISSING;
        }
        var from = (SessionState) current.get()[0];
        int version = (Integer) current.get()[1];
        var to = SessionTransitions.next(from, event);
        if (to.isEmpty()) {
            log.warn("Refused session transition: {} on {} from {} ({})", event, sessionId, from, reason);
            return Outcome.REFUSED;
        }
        if (expectedVersion != null && expectedVersion != version) {
            return Outcome.STALE;
        }
        var now = now();
        var hostAction = switch (hostChange) {
            case HostChange.Keep keep -> "keep";
            case HostChange.Assign assign -> "assign";
            case HostChange.Clear clear -> "clear";
        };
        jdbc.sql("""
                        update sessions.sessions
                        set state = :to, version = version + 1, state_changed_at = :now,
                            host_id = case :hostAction when 'assign' then cast(:hostId as uuid)
                                                       when 'clear' then null else host_id end,
                            price_paise_per_min = case :hostAction when 'assign' then cast(:price as integer)
                                                                   when 'clear' then null else price_paise_per_min end
                        where id = :id
                        """)
                .param("to", to.get().name())
                .param("now", Timestamp.from(now))
                .param("hostAction", hostAction)
                .param("hostId", hostChange instanceof HostChange.Assign assign ? assign.hostId() : null)
                .param("price", hostChange instanceof HostChange.Assign assign ? assign.pricePaisePerMin() : null)
                .param("id", sessionId)
                .update();
        event(sessionId, from, to.get(), reason, now);
        // Metering follows the attempts and closes a session's usage once it ends (ADR 0017).
        if (to.get() == SessionState.HOST_LOST) {
            events.publishEvent(new AttemptLost(sessionId, (Integer) current.get()[2]));
        }
        if (to.get().isFinal && (Boolean) current.get()[3]) {
            events.publishEvent(new SessionEnded(sessionId));
        }
        return Outcome.APPLIED;
    }

    /** The attempt stopped running now, on the control plane's clock; the first time counts. */
    @Transactional(propagation = Propagation.MANDATORY)
    void endAttempt(UUID sessionId, int attempt) {
        jdbc.sql("""
                        update sessions.attempts set ended_at = coalesce(ended_at, :now)
                        where session_id = :sessionId and attempt = :attempt
                        """)
                .param("now", Timestamp.from(now()))
                .param("sessionId", sessionId)
                .param("attempt", attempt)
                .update();
    }

    private void event(UUID sessionId, @Nullable SessionState from, SessionState to, String reason, Instant at) {
        jdbc.sql("""
                        insert into sessions.session_events (id, session_id, from_state, to_state, reason, at)
                        values (:id, :sessionId, :from, :to, :reason, :at)
                        """)
                .param("id", ids.newId())
                .param("sessionId", sessionId)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("reason", reason)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** The session, locked for the rest of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Session> lock(UUID sessionId) {
        return jdbc.sql("select * from sessions.sessions where id = :id for update")
                .param("id", sessionId)
                .query(SessionStore::session)
                .optional();
    }

    /** Whether the attempt's StartSession has gone down its host's stream. */
    boolean startDelivered(UUID sessionId, int attempt) {
        return jdbc.sql("""
                        select exists (select 1 from sessions.attempts
                                       where session_id = :id and attempt = :attempt and start_delivered_at is not null)
                        """)
                .param("id", sessionId)
                .param("attempt", attempt)
                .query(Boolean.class)
                .single();
    }

    Optional<Session> find(UUID sessionId) {
        return jdbc.sql("select * from sessions.sessions where id = :id")
                .param("id", sessionId)
                .query(SessionStore::session)
                .optional();
    }

    Optional<Session> findByKey(UUID renterId, String idempotencyKey) {
        return jdbc.sql("select * from sessions.sessions where renter_id = :renterId and idempotency_key = :key")
                .param("renterId", renterId)
                .param("key", idempotencyKey)
                .query(SessionStore::session)
                .optional();
    }

    List<Session> findByRenter(UUID renterId, int limit) {
        return jdbc.sql("""
                        select * from sessions.sessions where renter_id = :renterId
                        order by created_at desc, id desc limit :limit
                        """)
                .param("renterId", renterId)
                .param("limit", limit)
                .query(SessionStore::session)
                .list();
    }

    List<Event> events(UUID sessionId) {
        return jdbc.sql("""
                        select from_state, to_state, reason, at from sessions.session_events
                        where session_id = :sessionId order by at, id
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Event(
                        rs.getString("from_state"),
                        rs.getString("to_state"),
                        rs.getString("reason"),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    private static Session session(ResultSet rs, int row) throws SQLException {
        return new Session(
                rs.getObject("id", UUID.class),
                rs.getObject("renter_id", UUID.class),
                rs.getObject("host_id", UUID.class),
                rs.getString("type"),
                SessionState.valueOf(rs.getString("state")),
                rs.getInt("attempt"),
                rs.getString("template_id"),
                rs.getString("image_digest"),
                rs.getInt("vram_needed_mb"),
                rs.getInt("max_price_paise_per_min"),
                rs.getObject("price_paise_per_min", Integer.class),
                rs.getLong("max_spend_paise"),
                rs.getInt("max_runtime_seconds"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getTimestamp("state_changed_at").toInstant(),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs, "started_at"),
                rs.getString("error_code"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
