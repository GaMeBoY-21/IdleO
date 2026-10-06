package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.billing.CreditReservations;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A renter's sessions (ADR 0015). See the sessions module's package documentation. */
@RestController
@RequestMapping("/v1/sessions")
class SessionController {

    static final int LIST_LIMIT = 50;

    private final SessionService sessions;
    private final SessionOutputs outputs;
    private final SessionStore store;
    private final CreditReservations reservations;

    SessionController(
            SessionService sessions, SessionOutputs outputs, SessionStore store, CreditReservations reservations) {
        this.sessions = sessions;
        this.outputs = outputs;
        this.store = store;
        this.reservations = reservations;
    }

    record CreateSessionRequest(
            @NotBlank String templateId,
            @NotNull @Min(1) Integer maxPricePaisePerMin,
            @NotNull @Min(1) Long maxSpendPaise,
            @Nullable @Min(1) Integer vramNeededMb,
            @Nullable @Min(1) Integer maxRuntimeSeconds) {}

    record EventResponse(String state, String reason, Instant at) {}

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
            List<EventResponse> events,
            Instant createdAt,
            @Nullable Instant startedAt) {}

    record SessionList(List<SessionResponse> sessions) {}

    @PostMapping
    ResponseEntity<SessionResponse> create(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody CreateSessionRequest request) {
        var key = IdempotentRequests.parseKey(idempotencyKey);
        var created = sessions.create(
                user.id(),
                key,
                new SessionService.Request(
                        request.templateId(),
                        request.maxPricePaisePerMin(),
                        request.maxSpendPaise(),
                        request.vramNeededMb(),
                        request.maxRuntimeSeconds()));
        var body = view(created.session());
        return created.created()
                ? ResponseEntity.created(URI.create("/v1/sessions/" + body.id()))
                        .body(body)
                : ResponseEntity.ok(body);
    }

    @GetMapping
    SessionList list(@AuthenticationPrincipal CurrentUser user) {
        return new SessionList(store.findByRenter(user.id(), LIST_LIMIT).stream()
                .map(this::view)
                .toList());
    }

    @GetMapping("/{id}")
    SessionResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return view(sessions.owned(user.id(), id));
    }

    @GetMapping("/{id}/outputs")
    SessionOutputs.Outputs outputs(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return outputs.of(user.id(), id);
    }

    @PostMapping("/{id}/cancel")
    SessionResponse cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return view(sessions.cancel(user.id(), id));
    }

    private SessionResponse view(SessionStore.Session session) {
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
                reservations.held(session.id()).map(held -> held.value()).orElse(0L),
                store.events(session.id()).stream()
                        .map(event -> new EventResponse(event.toState(), event.reason(), event.at()))
                        .toList(),
                session.createdAt(),
                session.startedAt());
    }
}
