package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A renter's uploads (ADR 0019): one file each, which the client PUTs straight to storage in parts through
 * pre-signed links, then completes. A ready upload is a session's input by its id.
 */
@RestController
@RequestMapping("/v1/uploads")
class UploadController {

    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 100;
    /** Storage's limit on parts in one multipart upload. */
    static final int MAX_PARTS = 10_000;

    private final UploadService uploads;
    private final UploadStore store;

    UploadController(UploadService uploads, UploadStore store) {
        this.uploads = uploads;
        this.store = store;
    }

    record CreateUploadRequest(
            @NotBlank String filename, @NotNull @Min(1) Long sizeBytes) {}

    record PartLinksRequest(
            @NotEmpty @Size(max = MAX_PARTS) List<@NotNull @Min(1) @Max(MAX_PARTS) Integer> partNumbers) {}

    /** A part as uploaded: its number and the ETag storage returned for its PUT. */
    record UploadedPart(
            @NotNull @Min(1) @Max(MAX_PARTS) Integer number,
            @NotBlank String etag) {}

    record CompleteUploadRequest(
            @NotEmpty @Size(max = MAX_PARTS) List<@Valid UploadedPart> parts) {}

    /** Where to PUT one part. */
    record UploadPartLink(int number, String url) {}

    record UploadPartLinks(List<UploadPartLink> parts, Instant urlsExpireAt) {}

    /**
     * An upload.
     *
     * @param filename as the renter named it
     * @param partSizeBytes every part's size but the last, which may be smaller
     * @param expiresAt when it's aborted (pending) or deleted (ready), unless a session that hasn't ended uses it
     */
    record UploadResponse(
            UUID id,
            String filename,
            long sizeBytes,

            @Schema(allowableValues = {"pending", "ready", "failed", "expired", "deleted"})
            String status,

            long partSizeBytes,
            int partCount,
            Instant createdAt,
            @Nullable Instant completedAt,
            Instant expiresAt) {

        static UploadResponse of(UploadStore.Upload upload) {
            return new UploadResponse(
                    upload.id(),
                    upload.filename(),
                    upload.sizeBytes(),
                    UploadService.status(upload),
                    upload.partSizeBytes(),
                    upload.partCount(),
                    upload.createdAt(),
                    upload.completedAt(),
                    upload.expiresAt());
        }
    }

    /** A new upload, with a link to PUT each part to; complete it with each part's ETag. */
    record NewUpload(
            UUID id,
            String filename,
            long sizeBytes,

            @Schema(allowableValues = {"pending", "ready", "failed", "expired", "deleted"})
            String status,

            long partSizeBytes,
            int partCount,
            Instant createdAt,
            Instant expiresAt,
            List<UploadPartLink> parts,
            Instant urlsExpireAt) {}

    record UploadList(
            List<UploadResponse> uploads, @Nullable String nextCursor) {}

    @PostMapping
    @ApiResponse(
            responseCode = "201",
            description = "The new upload, with its part links",
            headers = @Header(name = "Location", description = "The upload's URL", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = NewUpload.class)))
    @ApiResponse(
            responseCode = "200",
            description = "A repeat with the same Idempotency-Key: the same upload, with fresh links",
            content = @Content(schema = @Schema(implementation = NewUpload.class)))
    ResponseEntity<NewUpload> create(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody CreateUploadRequest request) {
        var key = IdempotentRequests.parseKey(idempotencyKey);
        var created = uploads.create(user.id(), key, request.filename(), request.sizeBytes());
        var upload = created.upload();
        var links = upload.status() == UploadStore.Status.PENDING
                ? uploads.links(upload)
                : new UploadService.PartLinks(List.of(), uploads.now());
        var body = new NewUpload(
                upload.id(),
                upload.filename(),
                upload.sizeBytes(),
                UploadService.status(upload),
                upload.partSizeBytes(),
                upload.partCount(),
                upload.createdAt(),
                upload.expiresAt(),
                links.parts().stream()
                        .map(link -> new UploadPartLink(link.number(), link.url()))
                        .toList(),
                links.expireAt());
        return created.created()
                ? ResponseEntity.created(URI.create("/v1/uploads/" + upload.id()))
                        .body(body)
                : ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/part-urls")
    UploadPartLinks partUrls(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody PartLinksRequest request) {
        var links = uploads.freshLinks(user.id(), id, request.partNumbers());
        return new UploadPartLinks(
                links.parts().stream()
                        .map(link -> new UploadPartLink(link.number(), link.url()))
                        .toList(),
                links.expireAt());
    }

    @PostMapping("/{id}/complete")
    UploadResponse complete(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody CompleteUploadRequest request) {
        return UploadResponse.of(uploads.complete(
                user.id(),
                id,
                request.parts().stream()
                        .map(part -> new SessionStorage.PartEtag(part.number(), part.etag()))
                        .toList()));
    }

    @GetMapping
    UploadList list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        var size = Cursors.limit(limit, MAX_PAGE);
        var rows = store.page(user.id(), cursor == null ? null : Cursors.id(cursor), size + 1);
        var page = rows.subList(0, Math.min(size, rows.size()));
        return new UploadList(
                page.stream().map(UploadResponse::of).toList(),
                rows.size() > size ? Cursors.ofId(page.getLast().id()) : null);
    }

    @GetMapping("/{id}")
    UploadResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return UploadResponse.of(uploads.owned(user.id(), id));
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204", description = "Deleted, or already gone")
    ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        uploads.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
