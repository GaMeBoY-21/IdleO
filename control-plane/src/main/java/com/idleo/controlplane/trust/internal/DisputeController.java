package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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

/**
 * A renter reports a problem with a session they ran, and follows what we did about it (ADR 0028). Another person's
 * session or report is {@code not-found}.
 */
@RestController
@RequestMapping("/v1/disputes")
class DisputeController {

    static final String IDEMPOTENCY_TABLE = "trust.idempotency_keys";
    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 50;

    private final Disputes disputes;
    private final IdempotentRequests idempotency;

    DisputeController(Disputes disputes, IdempotentRequests idempotency) {
        this.disputes = disputes;
        this.idempotency = idempotency;
    }

    /**
     * @param reason {@code didnt_start}, {@code failed}, {@code slow}, {@code wrong_output}, {@code charged_wrong} or
     *     {@code other}
     * @param details what happened, up to 2,000 characters; empty or left out for none
     */
    record CreateDisputeRequest(
            @NotNull UUID sessionId,
            @NotBlank String reason,
            @Size(max = Disputes.MAX_DETAILS) @Nullable String details) {}

    /**
     * A report as its renter sees it.
     *
     * @param state {@code open}, {@code refunded} (resolved with a refund) or {@code not_refunded} (resolved without
     *     one)
     * @param resolutionReason our answer, once it's resolved
     * @param refundPaise what was refunded to the renter's credit, when it was
     */
    record DisputeResponse(
            UUID id,
            UUID sessionId,
            String reason,
            @Nullable String details,
            String state,
            Instant createdAt,
            @Nullable String resolutionReason,
            @Nullable Instant resolvedAt,
            @Nullable Long refundPaise) {}

    record DisputePage(
            List<DisputeResponse> disputes, @Nullable String nextCursor) {}

    /**
     * Reports a problem with an ended session, within 7 days of it ending: 409 {@code report-window-closed} after
     * that, and {@code report-already-open} while the session has a report we haven't resolved.
     */
    @PostMapping
    @ApiResponse(
            responseCode = "201",
            description = "The new report; a repeat with the same key returns the first response",
            headers = @Header(name = "Location", description = "The report's URL", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = DisputeResponse.class)))
    ResponseEntity<String> create(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody CreateDisputeRequest request) {
        var reason = request.reason().strip();
        if (!Disputes.REASONS.contains(reason)) {
            throw ApiProblem.invalid("reason", "must be one of " + String.join(", ", Disputes.REASONS) + ".");
        }
        if (request.details() != null && request.details().strip().length() > Disputes.MAX_DETAILS) {
            throw ApiProblem.invalid("details", "must be at most " + Disputes.MAX_DETAILS + " characters.");
        }
        return idempotency.execute(IDEMPOTENCY_TABLE, user.id(), idempotencyKey, request, () -> {
            return switch (disputes.open(user.id(), request.sessionId(), reason, request.details())) {
                case Disputes.Opened.Created created ->
                    ResponseEntity.created(URI.create(
                                    "/v1/disputes/" + created.dispute().id()))
                            .body(view(created.dispute()));
                case Disputes.Opened.NotFound _ ->
                    throw ApiProblem.of(Problem.NOT_FOUND, "There's no ended session with that id.");
                case Disputes.Opened.WindowClosed closed ->
                    throw ApiProblem.of(
                            Problem.REPORT_WINDOW_CLOSED,
                            "Reporting this session closed at " + closed.closedAt() + ".");
                case Disputes.Opened.AlreadyOpen open ->
                    throw ApiProblem.of(
                            Problem.REPORT_ALREADY_OPEN, "Report " + open.disputeId() + " is open for this session.");
            };
        });
    }

    /** The renter's reports, newest first; {@code session} keeps one session's. */
    @GetMapping
    DisputePage list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @Nullable UUID session,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiProblem.invalid("limit", "must be from 1 to " + MAX_PAGE + ".");
        }
        var rows = disputes.ofRenter(user.id(), session, after(cursor), limit + 1);
        var page = rows.size() > limit ? rows.subList(0, limit) : rows;
        return new DisputePage(
                page.stream().map(DisputeController::view).toList(),
                rows.size() > limit ? cursorAfter(page.getLast().id()) : null);
    }

    @GetMapping("/{id}")
    DisputeResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return disputes.find(id)
                .filter(dispute -> dispute.renterId().equals(user.id()))
                .map(DisputeController::view)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such report."));
    }

    private static DisputeResponse view(Disputes.Dispute dispute) {
        return new DisputeResponse(
                dispute.id(),
                dispute.sessionId(),
                dispute.reason(),
                dispute.details(),
                dispute.state(),
                dispute.createdAt(),
                dispute.resolutionReason(),
                dispute.resolvedAt(),
                dispute.refundPaise());
    }

    private static String cursorAfter(UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("d:" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static @Nullable UUID after(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var raw = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
            if (raw.startsWith("d:")) {
                return UUID.fromString(raw.substring(2));
            }
        } catch (IllegalArgumentException e) {
            // Refused below.
        }
        throw ApiProblem.invalid("cursor", "isn't a cursor this API gave out.");
    }
}
