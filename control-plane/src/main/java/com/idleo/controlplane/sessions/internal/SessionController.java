package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
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
    private final SessionViews views;

    SessionController(SessionService sessions, SessionOutputs outputs, SessionStore store, SessionViews views) {
        this.sessions = sessions;
        this.outputs = outputs;
        this.store = store;
        this.views = views;
    }

    record CreateSessionRequest(
            @NotBlank String templateId,
            @NotNull @Min(1) Integer maxPricePaisePerMin,
            @NotNull @Min(1) Long maxSpendPaise,
            @Nullable @Min(1) Integer vramNeededMb,
            @Nullable @Min(1) Integer maxRuntimeSeconds) {}

    record SessionList(List<SessionViews.SessionResponse> sessions) {}

    @PostMapping
    @ApiResponse(
            responseCode = "201",
            description = "The booked session",
            headers = @Header(name = "Location", description = "The session's URL", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = SessionViews.SessionResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "A repeat with the same Idempotency-Key: the session it booked",
            content = @Content(schema = @Schema(implementation = SessionViews.SessionResponse.class)))
    ResponseEntity<SessionViews.SessionResponse> create(
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
    SessionViews.SessionResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return view(sessions.owned(user.id(), id));
    }

    @GetMapping("/{id}/outputs")
    SessionOutputs.Outputs outputs(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return outputs.of(user.id(), id);
    }

    record StopRequest(@Nullable Boolean checkpointFirst) {}

    @PostMapping("/{id}/stop")
    SessionViews.SessionResponse stop(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @RequestBody(required = false) @Nullable StopRequest request) {
        boolean checkpointFirst = request == null || request.checkpointFirst() == null || request.checkpointFirst();
        return view(sessions.stop(user.id(), id, checkpointFirst));
    }

    @PostMapping("/{id}/cancel")
    SessionViews.SessionResponse cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return view(sessions.cancel(user.id(), id));
    }

    private SessionViews.SessionResponse view(SessionStore.Session session) {
        return views.of(session);
    }
}
