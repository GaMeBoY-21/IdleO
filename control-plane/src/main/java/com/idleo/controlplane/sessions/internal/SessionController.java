package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.sessions.CreateSessionRequest;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A renter's sessions (ADR 0015). See the sessions module's package documentation. */
@RestController
@RequestMapping("/v1/sessions")
class SessionController {

    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 100;

    private final SessionService sessions;
    private final SessionOutputs outputs;
    private final SessionStore store;
    private final SessionViews views;
    private final SessionLogs logs;

    SessionController(
            SessionService sessions, SessionOutputs outputs, SessionStore store, SessionViews views, SessionLogs logs) {
        this.sessions = sessions;
        this.outputs = outputs;
        this.store = store;
        this.views = views;
        this.logs = logs;
    }

    /** @param nextCursor pass it back as {@code cursor} for the next page; null on the last one */
    record SessionList(
            List<SessionViews.SessionResponse> sessions,
            @Nullable String nextCursor) {}

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
        var created = sessions.create(user.id(), key, request);
        var body = view(created.session());
        return created.created()
                ? ResponseEntity.created(URI.create("/v1/sessions/" + body.id()))
                        .body(body)
                : ResponseEntity.ok(body);
    }

    /**
     * The renter's sessions, newest first, a page at a time; {@code active=true} only those that haven't ended,
     * {@code active=false} only those that have.
     */
    @GetMapping
    SessionList list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit,
            @RequestParam(required = false) @Nullable Boolean active) {
        var size = Cursors.limit(limit, MAX_PAGE);
        var rows = store.page(user.id(), cursor == null ? null : Cursors.id(cursor), active, size + 1);
        var page = rows.subList(0, Math.min(size, rows.size()));
        return new SessionList(
                page.stream().map(this::view).toList(),
                rows.size() > size ? Cursors.ofId(page.getLast().id()) : null);
    }

    @GetMapping("/{id}")
    SessionViews.SessionResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return view(sessions.owned(user.id(), id));
    }

    @GetMapping("/{id}/outputs")
    SessionOutputs.Outputs outputs(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return outputs.of(user.id(), id);
    }

    static final int LOG_PAGE = 20;
    static final int MAX_LOG_PAGE = 100;

    /** One chunk of a job's output, as uploaded by its agent. */
    record LogChunk(int attempt, int seq, String text) {}

    /**
     * Some of a session's logs.
     *
     * @param nextCursor where to continue from, now or once more output arrives; null once complete
     * @param complete the session is settled and every chunk has been returned: there will be no more
     */
    record SessionLogPage(List<LogChunk> chunks, @Nullable String nextCursor, boolean complete) {}

    /**
     * The job's output, oldest first: attempt, then seq, as UTF-8 text with invalid bytes replaced. Chunks are
     * counted, not lines: {@code limit} is the most chunks returned, each at most 64 KiB. Only the renter reads them.
     */
    @GetMapping("/{id}/logs")
    SessionLogPage logs(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + LOG_PAGE) int limit) {
        var session = sessions.owned(user.id(), id);
        var size = Cursors.limit(limit, MAX_LOG_PAGE);
        // Settled first: a chunk reported after this read isn't missed, it's only next time's.
        boolean settled = views.settled(session);
        var after = cursor == null ? new int[] {1, 0} : Cursors.chunk(cursor);
        var page = logs.page(id, after[0], after[1], size);
        boolean complete = settled && !page.more();
        return new SessionLogPage(
                page.chunks().stream()
                        .map(chunk -> new LogChunk(chunk.attempt(), chunk.seq(), chunk.text()))
                        .toList(),
                complete ? null : SessionLogs.cursorAfter(page, cursor),
                complete);
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
