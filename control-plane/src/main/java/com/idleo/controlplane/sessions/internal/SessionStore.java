package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.sessions.AttemptLost;
import com.idleo.controlplane.sessions.SessionEnded;
import com.idleo.controlplane.sessions.SessionNeverRan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

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
    private final SessionHints hints;
    private final SessionMetrics metrics;

    SessionStore(
            JdbcClient jdbc,
            IdGenerator ids,
            Clock clock,
            ApplicationEventPublisher events,
            SessionHints hints,
            SessionMetrics metrics) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.events = events;
        this.hints = hints;
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
            @Nullable String errorCode,
            Map<String, Object> params,
            Map<String, String> presets,
            List<Input> inputs,
            @Nullable UUID imageId) {

        Session {
            params = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(params));
            presets = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(presets));
            inputs = List.copyOf(inputs);
        }

        /** The uploads among its inputs. */
        List<UUID> uploadIds() {
            return inputs.stream().map(Input::uploadId).filter(Objects::nonNull).toList();
        }
    }

    /** One of a session's inputs, by the template's name for it: exactly one of an upload or a link. */
    record Input(
            String name, @Nullable UUID uploadId, @Nullable String url) {}

    /** Every session column, and its inputs as JSON, {@code inputs_json}. */
    private static final String SELECT = """
            select s.*, (select coalesce(jsonb_agg(jsonb_build_object(
                                'name', i.name, 'uploadId', i.upload_id, 'url', i.url) order by i.name), '[]')
                         from sessions.session_inputs i where i.session_id = s.id) as inputs_json
            from sessions.sessions s
            """;

    /**
     * Parameters as JSON in {@code sessions.params}, read back as they were checked: whole numbers as Long, others as
     * BigDecimal, never a double that would print differently.
     */
    private static final JsonMapper PARAMS = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build();

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
                             idempotency_key, request_hash, state_changed_at, version, created_at, params,
                             presets, image_id)
                        values (:id, :renterId, null, :type, :state, 1, :templateId, :image, :vram,
                                :maxPrice, null, :maxSpend, :maxRuntime, :key, :hash, :now, 0, :now,
                                cast(:params as jsonb), cast(:presets as jsonb), :imageId)
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
                .param("params", PARAMS.writeValueAsString(session.params()))
                .param("presets", PARAMS.writeValueAsString(session.presets()))
                .param("imageId", session.imageId())
                .update();
        if (inserted == 1) {
            for (var input : session.inputs()) {
                jdbc.sql("""
                                insert into sessions.session_inputs (session_id, name, upload_id, url)
                                values (:sessionId, :name, :uploadId, :url)
                                """)
                        .param("sessionId", session.id())
                        .param("name", input.name())
                        .param("uploadId", input.uploadId())
                        .param("url", input.url())
                        .update();
            }
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
        record Current(
                SessionState state,
                int version,
                int attempt,
                UUID renterId,
                Instant createdAt,
                @Nullable Instant startedAt) {}
        var current = jdbc.sql("""
                        select state, version, attempt, renter_id, created_at, started_at from sessions.sessions
                        where id = :id for update
                        """)
                .param("id", sessionId)
                .query((rs, n) -> new Current(
                        SessionState.valueOf(rs.getString("state")),
                        rs.getInt("version"),
                        rs.getInt("attempt"),
                        rs.getObject("renter_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("started_at") == null
                                ? null
                                : rs.getTimestamp("started_at").toInstant()))
                .optional();
        if (current.isEmpty()) {
            return Outcome.MISSING;
        }
        var from = current.get().state();
        int version = current.get().version();
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
        hints.changed(sessionId);
        if (to.get().isFinal) {
            metrics.ended(to.get(), reason);
        } else if (to.get() == SessionState.PROVISIONING && current.get().attempt() == 1) {
            metrics.gpuFound(Duration.between(current.get().createdAt(), now));
        }
        // Metering follows the attempts and closes a session's usage once it ends (ADR 0017).
        if (to.get() == SessionState.HOST_LOST) {
            events.publishEvent(new AttemptLost(sessionId, current.get().attempt()));
        }
        var renterId = current.get().renterId();
        var startedAt = current.get().startedAt();
        if (to.get().isFinal && startedAt != null) {
            events.publishEvent(new SessionEnded(
                    sessionId, renterId, lastHostThatRan(sessionId), to.get().name(), startedAt, now));
        } else if (to.get() == SessionState.NO_HOST_FREE || to.get() == SessionState.FAILED) {
            events.publishEvent(
                    new SessionNeverRan(sessionId, renterId, to.get().name(), now));
        }
        return Outcome.APPLIED;
    }

    /** The host of the session's last attempt that ran: a started session always has one. */
    private UUID lastHostThatRan(UUID sessionId) {
        return jdbc.sql("""
                        select host_id from sessions.attempts
                        where session_id = :id and running_at is not null
                        order by attempt desc limit 1
                        """).param("id", sessionId).query(UUID.class).single();
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
        return jdbc.sql(SELECT + "where s.id = :id for update of s")
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
        return jdbc.sql(SELECT + "where s.id = :id")
                .param("id", sessionId)
                .query(SessionStore::session)
                .optional();
    }

    Optional<Session> findByKey(UUID renterId, String idempotencyKey) {
        return jdbc.sql(SELECT + "where s.renter_id = :renterId and s.idempotency_key = :key")
                .param("renterId", renterId)
                .param("key", idempotencyKey)
                .query(SessionStore::session)
                .optional();
    }

    /**
     * The renter's sessions, newest first by id (UUIDv7), after {@code after} if given; only live ones, only ended
     * ones, or both. At most {@code limit}.
     */
    List<Session> page(UUID renterId, @Nullable UUID after, @Nullable Boolean active, int limit) {
        var ended = "state in (" + UploadStore.FINAL_STATES + ")";
        var filter = active == null ? "" : active ? " and not " + ended : " and " + ended;
        return jdbc.sql(SELECT + "where s.renter_id = :renterId"
                        + " and (cast(:after as uuid) is null or s.id < cast(:after as uuid))" + filter
                        + " order by s.id desc limit :limit")
                .param("renterId", renterId)
                .param("after", after)
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
                rs.getString("error_code"),
                params(rs.getString("params")),
                presets(rs.getString("presets")),
                inputs(rs.getString("inputs_json")),
                rs.getObject("image_id", UUID.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> presets(@Nullable String json) {
        return json == null ? Map.of() : PARAMS.readValue(json, java.util.LinkedHashMap.class);
    }

    private static List<Input> inputs(String json) {
        var inputs = new java.util.ArrayList<Input>();
        for (var input : PARAMS.readTree(json)) {
            var uploadId = input.get("uploadId");
            var url = input.get("url");
            inputs.add(new Input(
                    input.get("name").asString(),
                    uploadId == null || uploadId.isNull() ? null : UUID.fromString(uploadId.asString()),
                    url == null || url.isNull() ? null : url.asString()));
        }
        return inputs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(@Nullable String json) {
        return json == null ? Map.of() : PARAMS.readValue(json, java.util.LinkedHashMap.class);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
