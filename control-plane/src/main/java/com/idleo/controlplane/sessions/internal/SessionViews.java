package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.agentgateway.AgentPresence;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.hosts.HostGpus;
import com.idleo.controlplane.sessions.CreateSessionRequest;
import com.idleo.controlplane.sessions.SessionCosts;
import com.idleo.controlplane.sessions.Template;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one view of a session for its renter (ADR 0019): {@code GET /v1/sessions/{id}}, each row of the list, and
 * every {@code session} event on its stream are built here, so they never differ.
 */
@Component
class SessionViews {

    private final SessionStore store;
    private final CreditReservations reservations;
    private final ObjectProvider<SessionCosts> costs;
    private final HostGpus gpus;
    private final JdbcClient jdbc;
    private final TemplateCatalog catalog;
    private final AgentPresence presence;

    SessionViews(
            SessionStore store,
            CreditReservations reservations,
            ObjectProvider<SessionCosts> costs,
            HostGpus gpus,
            JdbcClient jdbc,
            TemplateCatalog catalog,
            AgentPresence presence) {
        this.store = store;
        this.reservations = reservations;
        this.costs = costs;
        this.gpus = gpus;
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.presence = presence;
    }

    record EventResponse(String state, String reason, Instant at) {}

    /**
     * The GPU that runs or ran the session's current or latest attempt: its model and memory, never the host.
     *
     * @param utilPct how busy it is now, 0 to 100, while the session runs; null when unknown or stale (ADR 0021)
     * @param tempC its temperature now, while the session runs; null when unknown or stale
     */
    record SessionGpu(
            String model,
            int vramMb,
            @Nullable Integer utilPct,
            @Nullable Integer tempC) {}

    /** One input: an upload of the renter's, or an https link the job fetches itself. */
    record SessionInputView(
            @Nullable UUID uploadId, @Nullable String url) {}

    /** A complete checkpoint: its marker is in storage, written at {@code completedAt} on storage's clock. */
    record SessionCheckpoint(int attempt, int seq, Instant completedAt) {}

    /**
     * A renter's session.
     *
     * @param reservedPaise the credit held for it now
     * @param chargedPaise settled, less anything given back
     * @param accruedPaise billable by both witnesses but not settled yet
     * @param settled nothing about it will change any more: it has ended, its last settlement is posted and its hold
     *     released
     * @param endedAt when it reached its final state
     * @param gpu the GPU of its current or latest attempt; null before a host accepted it
     * @param checkpoints its complete checkpoints, oldest first
     * @param params its parameters, with presets and the template's defaults filled in
     * @param presets the option chosen for each of its template's preset questions
     * @param inputs what it reads, by the template's input names, in the template's order
     */
    record SessionResponse(
            UUID id,
            String state,
            int attempt,
            @Nullable String errorCode,
            String templateId,
            @Nullable UUID hostId,
            @Nullable Integer pricePaisePerMin,
            int maxPricePaisePerMin,
            long maxSpendPaise,
            int vramNeededMb,
            int maxRuntimeSeconds,
            long reservedPaise,
            long chargedPaise,
            long accruedPaise,
            boolean lowCredit,
            boolean settled,
            List<EventResponse> events,
            Instant createdAt,
            @Nullable Instant startedAt,
            @Nullable Instant endedAt,
            @Nullable SessionGpu gpu,
            List<SessionCheckpoint> checkpoints,

            @Schema(additionalPropertiesSchema = CreateSessionRequest.ParamValue.class)
            Map<String, Object> params,

            Map<String, String> presets,
            Map<String, SessionInputView> inputs) {}

    SessionResponse of(SessionStore.Session session) {
        var costs = this.costs.getIfAvailable();
        var cost = costs == null ? SessionCosts.Cost.NONE : costs.of(session.id());
        var held = reservations.held(session.id());
        boolean ended = session.state().isFinal;
        boolean settled = settled(session, held.isPresent(), cost);
        boolean matched = session.state() != SessionState.REQUESTED && session.hostId() != null;
        return new SessionResponse(
                session.id(),
                session.state().name(),
                session.attempt(),
                session.errorCode(),
                session.templateId(),
                matched ? session.hostId() : null,
                session.pricePaisePerMin(),
                session.maxPricePaisePerMin(),
                session.maxSpendPaise(),
                session.vramNeededMb(),
                session.maxRuntimeSeconds(),
                held.map(paise -> paise.value()).orElse(0L),
                cost.chargedPaise(),
                cost.accruedPaise(),
                cost.lowCredit(),
                settled,
                store.events(session.id()).stream()
                        .map(event -> new EventResponse(event.toState(), event.reason(), event.at()))
                        .toList(),
                session.createdAt(),
                session.startedAt(),
                ended ? session.stateChangedAt() : null,
                gpu(session.id(), session.state() == SessionState.RUNNING),
                checkpoints(session.id()),
                params(session),
                session.presets(),
                inputs(session));
    }

    /** The session's inputs in its template's order. */
    private Map<String, SessionInputView> inputs(SessionStore.Session session) {
        var order = catalog.find(session.templateId())
                .map(template ->
                        template.inputs().stream().map(Template.Input::name).toList())
                .orElse(List.of());
        var ordered = new LinkedHashMap<String, SessionInputView>();
        session.inputs().stream()
                .sorted(java.util.Comparator.comparingInt(input -> {
                    int at = order.indexOf(input.name());
                    return at < 0 ? Integer.MAX_VALUE : at;
                }))
                .forEach(input -> ordered.put(input.name(), new SessionInputView(input.uploadId(), input.url())));
        return ordered;
    }

    /** The session's parameters in its template's order. */
    private Map<String, Object> params(SessionStore.Session session) {
        var ordered = new LinkedHashMap<String, Object>();
        catalog.find(session.templateId())
                .ifPresent(template -> template.params().stream()
                        .map(Template.Param::name)
                        .filter(session.params()::containsKey)
                        .forEach(name -> ordered.put(name, session.params().get(name))));
        session.params().forEach(ordered::putIfAbsent);
        return ordered;
    }

    /** Nothing about the session will change any more. */
    boolean settled(SessionStore.Session session) {
        var costs = this.costs.getIfAvailable();
        return settled(
                session,
                reservations.held(session.id()).isPresent(),
                costs == null ? SessionCosts.Cost.NONE : costs.of(session.id()));
    }

    /** A session that ran is settled once metering has closed it; one that never ran, once its hold is gone. */
    private static boolean settled(SessionStore.Session session, boolean held, SessionCosts.Cost cost) {
        return session.state().isFinal && !held && (session.startedAt() == null || cost.closed());
    }

    /** Its GPU, with what presence says of it now when {@code live}: only a running session's renter sees that. */
    private @Nullable SessionGpu gpu(UUID sessionId, boolean live) {
        record Ran(UUID hostId, String gpuUuid) {}
        return jdbc.sql("""
                        select host_id, gpu_uuids[1] as gpu_uuid from sessions.attempts
                        where session_id = :sessionId order by attempt desc limit 1
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Ran(rs.getObject("host_id", UUID.class), rs.getString("gpu_uuid")))
                .optional()
                .flatMap(ran -> gpus.gpu(ran.hostId(), ran.gpuUuid()).map(gpu -> {
                    var reading = live ? reading(ran.hostId(), ran.gpuUuid()) : null;
                    return new SessionGpu(
                            gpu.model(),
                            gpu.vramMb(),
                            reading == null ? null : reading.utilPct(),
                            reading == null ? null : reading.tempC());
                }))
                .orElse(null);
    }

    /** The GPU's reading from its host's presence; null when the host isn't live (its last heartbeat is too old). */
    private AgentPresence.@Nullable GpuReading reading(UUID hostId, String gpuUuid) {
        var agent = presence.live(List.of(hostId)).get(hostId);
        return agent == null ? null : agent.gpu(gpuUuid).orElse(null);
    }

    private List<SessionCheckpoint> checkpoints(UUID sessionId) {
        return jdbc.sql("""
                        select attempt, step, completed_at from sessions.checkpoints
                        where session_id = :sessionId order by completed_at, attempt, step
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new SessionCheckpoint(
                        rs.getInt("attempt"),
                        rs.getInt("step"),
                        rs.getTimestamp("completed_at").toInstant()))
                .list();
    }
}
