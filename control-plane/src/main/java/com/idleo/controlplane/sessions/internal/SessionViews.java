package com.idleo.controlplane.sessions.internal;

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

    SessionViews(
            SessionStore store,
            CreditReservations reservations,
            ObjectProvider<SessionCosts> costs,
            HostGpus gpus,
            JdbcClient jdbc,
            TemplateCatalog catalog) {
        this.store = store;
        this.reservations = reservations;
        this.costs = costs;
        this.gpus = gpus;
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    record EventResponse(String state, String reason, Instant at) {}

    /** The GPU that runs or ran the session's current or latest attempt: its model and memory, never the host. */
    record SessionGpu(String model, int vramMb) {}

    /** The job's input: an upload of the renter's, or an https URL the job fetches itself. */
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
     * @param params its parameters, with the template's defaults filled in
     * @param input the file it reads; null if it reads none
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

            @Nullable SessionInputView input) {}

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
                gpu(session.id()),
                checkpoints(session.id()),
                params(session),
                session.inputUploadId() == null && session.inputUrl() == null
                        ? null
                        : new SessionInputView(session.inputUploadId(), session.inputUrl()));
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

    private @Nullable SessionGpu gpu(UUID sessionId) {
        record Ran(UUID hostId, String gpuUuid) {}
        return jdbc.sql("""
                        select host_id, gpu_uuids[1] as gpu_uuid from sessions.attempts
                        where session_id = :sessionId order by attempt desc limit 1
                        """)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Ran(rs.getObject("host_id", UUID.class), rs.getString("gpu_uuid")))
                .optional()
                .flatMap(ran -> gpus.gpu(ran.hostId(), ran.gpuUuid()))
                .map(gpu -> new SessionGpu(gpu.model(), gpu.vramMb()))
                .orElse(null);
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
