package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.RequiresAcceptedTerms;
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
 * A renter's uploads, My files (ADR 0019, ADR 0022): a folder of files or one file, each file PUT by the client
 * straight to storage in parts through pre-signed links asked for as it goes, then completed. A ready upload is a
 * session's input by its id, as often as the renter likes.
 */
@RestController
@RequestMapping("/v1/uploads")
class UploadController {

    static final int DEFAULT_PAGE = 20;
    static final int MAX_PAGE = 100;
    /** Storage's limit on parts in one multipart upload. */
    static final int MAX_PARTS = 10_000;
    /** The most files one complete call takes; a folder of more completes over several calls. */
    static final int MAX_COMPLETE = 100;

    private final UploadService uploads;
    private final UploadParts parts;
    private final UploadStore store;
    private final UploadFileStore files;

    UploadController(UploadService uploads, UploadParts parts, UploadStore store, UploadFileStore files) {
        this.uploads = uploads;
        this.parts = parts;
        this.store = store;
        this.files = files;
    }

    /** A file to upload: its path relative to the upload (a folder's {@code config.json}), and its size. */
    record UploadFileRequest(
            @NotBlank String path, @NotNull @Min(0) Long sizeBytes) {}

    /**
     * @param name what My files calls it: the folder's name, or the file's
     * @param files at most 1,000, the upload at most 40 GiB together
     */
    record CreateUploadRequest(
            @NotBlank String name,
            @NotEmpty @Size(max = MAX_PARTS) List<@Valid @NotNull UploadFileRequest> files) {}

    /** Part links for one file: its path as created, and up to 100 part numbers. */
    record PartLinksRequest(
            @NotBlank String path,

            @NotEmpty @Size(max = UploadParts.MAX_LINKS) List<@NotNull @Min(1) @Max(MAX_PARTS) Integer> partNumbers) {}

    /** A part as uploaded: its number and the ETag storage returned for its PUT. */
    record UploadedPart(
            @NotNull @Min(1) @Max(MAX_PARTS) Integer number,
            @NotBlank String etag) {}

    /** One file's parts, every one of them; none for an empty file. */
    record CompletedFile(
            @NotBlank String path,
            @NotNull @Size(max = MAX_PARTS) List<@Valid @NotNull UploadedPart> parts) {}

    record CompleteUploadRequest(
            @NotEmpty @Size(max = MAX_COMPLETE) List<@Valid @NotNull CompletedFile> files) {}

    /** Where to PUT one part. */
    record UploadPartLink(int number, String url) {}

    record UploadPartLinks(List<UploadPartLink> parts, Instant urlsExpireAt) {}

    /**
     * An upload, as My files lists it.
     *
     * @param name as the renter named it
     * @param sizeBytes all its files together
     * @param partSizeBytes every part's size but each file's last, which may be smaller
     * @param lastUsedAt when a session last took it as an input; null if none has
     * @param expiresAt when it's aborted (pending) or deleted (ready): 30 days after its last use, never while a
     *     session that hasn't ended uses it
     */
    record UploadResponse(
            UUID id,
            String name,
            long sizeBytes,
            int fileCount,

            @Schema(allowableValues = {"pending", "ready", "failed", "expired", "deleted"})
            String status,

            long partSizeBytes,
            Instant createdAt,
            @Nullable Instant completedAt,
            @Nullable Instant lastUsedAt,
            Instant expiresAt) {

        static UploadResponse of(UploadStore.Upload upload) {
            return new UploadResponse(
                    upload.id(),
                    upload.name(),
                    upload.sizeBytes(),
                    upload.fileCount(),
                    UploadService.status(upload),
                    upload.partSizeBytes(),
                    upload.createdAt(),
                    upload.completedAt(),
                    upload.lastUsedAt(),
                    upload.expiresAt());
        }
    }

    /**
     * One file of an upload.
     *
     * @param partCount how many parts it's sent in; 0 for an empty file
     * @param done it's complete at its declared size
     */
    record UploadFileResponse(String path, long sizeBytes, int partCount, boolean done) {}

    /** An upload with its files, by path. */
    record UploadDetail(UploadResponse upload, List<UploadFileResponse> files) {}

    record UploadList(
            List<UploadResponse> uploads, @Nullable String nextCursor) {}

    @RequiresAcceptedTerms
    @PostMapping
    @ApiResponse(
            responseCode = "201",
            description = "The new upload, with its files; ask for each file's part links as it's sent",
            headers = @Header(name = "Location", description = "The upload's URL", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = UploadDetail.class)))
    @ApiResponse(
            responseCode = "200",
            description = "A repeat with the same Idempotency-Key: the same upload, as it stands",
            content = @Content(schema = @Schema(implementation = UploadDetail.class)))
    ResponseEntity<UploadDetail> create(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody CreateUploadRequest request) {
        var key = IdempotentRequests.parseKey(idempotencyKey);
        var created = uploads.create(
                user.id(),
                key,
                request.name(),
                request.files().stream()
                        .map(file -> new UploadService.FileSpec(file.path(), file.sizeBytes()))
                        .toList());
        var body = detail(created.upload());
        return created.created()
                ? ResponseEntity.created(
                                URI.create("/v1/uploads/" + created.upload().id()))
                        .body(body)
                : ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/part-urls")
    UploadPartLinks partUrls(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody PartLinksRequest request) {
        var links = parts.links(user.id(), id, request.path(), request.partNumbers());
        return new UploadPartLinks(
                links.parts().stream()
                        .map(link -> new UploadPartLink(link.number(), link.url()))
                        .toList(),
                links.expireAt());
    }

    @PostMapping("/{id}/complete")
    UploadDetail complete(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody CompleteUploadRequest request) {
        return detail(parts.complete(
                user.id(),
                id,
                request.files().stream()
                        .map(file -> new UploadParts.FileParts(
                                file.path(),
                                file.parts().stream()
                                        .map(part -> new SessionStorage.PartEtag(part.number(), part.etag()))
                                        .toList()))
                        .toList()));
    }

    /** My files: the renter's pending and ready uploads, newest first. */
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
    UploadDetail get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return detail(uploads.owned(user.id(), id));
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204", description = "Deleted, or already gone")
    ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        uploads.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    private UploadDetail detail(UploadStore.Upload upload) {
        return new UploadDetail(
                UploadResponse.of(upload),
                files.files(upload.id()).stream()
                        .map(file -> new UploadFileResponse(
                                file.path(), file.sizeBytes(), file.partCount(upload.partSizeBytes()), file.done()))
                        .toList());
    }
}
