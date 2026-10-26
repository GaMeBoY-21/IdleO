package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.RequiresAcceptedTerms;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A renter's own images and their checks (ADR 0042). See the images module's documentation. */
@RestController
@RequestMapping("/v1/images")
@ConditionalOnWebApplication
class ImageController {

    static final String IDEMPOTENCY_TABLE = "images.idempotency_keys";

    private final ImageService service;
    private final IdempotentRequests idempotency;
    private final ImageProperties properties;

    ImageController(ImageService service, IdempotentRequests idempotency, ImageProperties properties) {
        this.service = service;
        this.idempotency = idempotency;
        this.properties = properties;
    }

    /**
     * An image to check: a reference (a tag or a digest, on Docker Hub, GHCR, quay.io, NGC or another public registry), and
     * for a private registry its user name and a read-only token, which is kept sealed, used only to pull the image, and
     * deleted after 30 days unused.
     */
    record NewImage(
            @NotBlank @Size(max = 512) @Schema(example = "pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime")
            String reference,

            @Nullable @Size(max = 255) String registryUsername,

            @Nullable @Size(max = 4096) @Schema(description = "A read-only token. Never shown back.")
            String registryToken) {

        @Override
        public String toString() {
            return "NewImage[reference=" + reference + ", registryUsername=" + registryUsername
                    + ", registryToken=(never printed)]";
        }
    }

    /** The check's progress while it runs. */
    record ImageProgress(
            @Schema(allowableValues = {"downloading", "reading", "scanning", "blocklist"})
            String phase,

            long doneBytes,
            long totalBytes) {}

    /** A warning: never a reason to block. */
    record ImageWarning(
            @Schema(allowableValues = {"secret", "vulnerabilities", "root_user", "no_shell"})
            String code,

            String message) {}

    /** Known vulnerabilities by severity. */
    record VulnerabilityCounts(int critical, int high, int medium, int low) {}

    /**
     * What the check found.
     *
     * @param cudaNeeded the CUDA version the image needs ({@code 12.4}); only hosts whose driver runs it are offered it
     * @param entrypoint the image's own entrypoint, run when the booking leaves the command empty
     * @param cmd the image's own command
     * @param workingDir where the job runs; {@code /work} when the image names none
     * @param user the image's USER; the job always runs as 10001
     */
    record ImageResult(
            String digest,
            String pinnedReference,
            long compressedBytes,
            long unpackedBytes,
            int layers,
            @Nullable String cudaNeeded,
            List<String> entrypoint,
            List<String> cmd,
            @Nullable String workingDir,
            @Nullable String user,
            boolean hasShell,
            VulnerabilityCounts vulnerabilities,
            List<ImageWarning> warnings,
            Instant checkedAt) {}

    /**
     * A renter's image and its check.
     *
     * @param state {@code queued} and {@code checking} until the check ends; {@code ready} can be booked; {@code blocked}
     *     never can; {@code failed} means the check didn't finish and nothing was decided: check it again
     * @param reason why it's blocked or failed, as a code
     * @param detail the same in plain words
     */
    record ImageResponse(
            UUID id,
            String reference,
            boolean privateRegistry,

            @Schema(allowableValues = {"queued", "checking", "ready", "blocked", "failed"})
            String state,

            @Nullable
            @Schema(
                    allowableValues = {
                        "wrong_platform",
                        "too_large",
                        "too_many_layers",
                        "too_large_unpacked",
                        "unsafe_path",
                        "unpack_failed",
                        "pull_failed",
                        "blocklisted",
                        "admin",
                        "not_checked"
                    })
            String reason,

            @Nullable String detail,
            @Nullable ImageProgress progress,
            @Nullable ImageResult result,
            Instant createdAt,
            @Nullable Instant checkedAt) {}

    /**
     * The renter's images, newest first, and whether they may use their own images at all.
     *
     * @param whyNot what to do to use one, when they can't
     */
    record ImageList(
            List<ImageResponse> images,
            boolean canUse,
            @Nullable String whyNot,
            String platform) {}

    /** The full list of what Trivy found, most severe first (at most 5,000). */
    record VulnerabilityList(List<VulnerabilityEntry> vulnerabilities, VulnerabilityCounts counts) {}

    record VulnerabilityEntry(
            @Schema(allowableValues = {"CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"})
            String severity,

            String id,
            String packageName,
            String installed,
            String fixed,
            String title) {}

    /**
     * Submits an image to be checked: 201 with its check, queued. 403 {@code verified-pan-required} for a renter without a
     * verified PAN; 422 {@code validation-failed} for a reference that isn't one (or names an address, not a public
     * registry). A repeat with the same Idempotency-Key returns the first answer.
     */
    @RequiresAcceptedTerms
    @PostMapping
    @ApiResponse(
            responseCode = "201",
            description = "The image, its check queued",
            headers = @Header(name = "Location", description = "The image's URL", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = ImageResponse.class)))
    ResponseEntity<String> submit(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody NewImage request) {
        return idempotency.execute(IDEMPOTENCY_TABLE, user.id(), idempotencyKey, request, () -> {
            var row = service.submit(
                    user.id(),
                    new ImageService.Submission(
                            request.reference(), request.registryUsername(), request.registryToken()));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .location(URI.create("/v1/images/" + row.id()))
                    .body(view(row));
        });
    }

    @GetMapping
    ImageList mine(@AuthenticationPrincipal CurrentUser user) {
        var allowed = service.allowed(user.id());
        return new ImageList(
                service.mine(user.id()).stream().map(this::view).toList(),
                allowed,
                allowed ? null : ImageService.WHY_NOT,
                properties.platform());
    }

    @GetMapping("/{id}")
    ImageResponse one(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return view(service.own(user.id(), id));
    }

    @GetMapping("/{id}/vulnerabilities")
    VulnerabilityList vulnerabilities(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        var row = service.own(user.id(), id);
        var counts = service.scanOf(row)
                .map(scan -> new VulnerabilityCounts(scan.critical(), scan.high(), scan.medium(), scan.low()))
                .orElse(new VulnerabilityCounts(0, 0, 0, 0));
        return new VulnerabilityList(
                service.vulnerabilities(user.id(), id).stream()
                        .map(v -> new VulnerabilityEntry(
                                v.severity(), v.id(), v.packageName(), v.installed(), v.fixed(), v.title()))
                        .toList(),
                counts);
    }

    /** Removes the renter's image, and its token unless another image of theirs uses it: 204. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        service.remove(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    private ImageResponse view(ImageStore.Row row) {
        var checking = row.state() == ImageStore.State.QUEUED || row.state() == ImageStore.State.CHECKING;
        var progress = checking && row.phase() != null
                ? new ImageProgress(row.phase(), row.doneBytes(), row.totalBytes())
                : null;
        var result = service.scanOf(row)
                .map(scan -> new ImageResult(
                        scan.digest(),
                        row.registry() + "/" + row.repository() + "@" + scan.digest(),
                        scan.compressedBytes(),
                        scan.unpackedBytes(),
                        scan.layers(),
                        scan.cudaNeeded(),
                        scan.entrypoint(),
                        scan.cmd(),
                        scan.workingDir(),
                        scan.user(),
                        scan.hasShell(),
                        new VulnerabilityCounts(scan.critical(), scan.high(), scan.medium(), scan.low()),
                        ImageVerdicts.warnings(scan).stream()
                                .map(warning -> new ImageWarning(warning.code(), warning.message()))
                                .toList(),
                        scan.checkedAt()))
                .orElse(null);
        return new ImageResponse(
                row.id(),
                row.reference(),
                row.isPrivate(),
                row.state().db(),
                row.reason(),
                row.detail(),
                progress,
                result,
                row.createdAt(),
                row.checkedAt());
    }
}
