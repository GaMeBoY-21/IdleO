package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.images.CheckedImages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The scanner's internal API (ADR 0042), reached only through {@link ScannerAccess}: on its own port, with its token. Not
 * part of the public API contract (api/openapi.yaml covers {@code /v1} and {@code /admin/v1} only). A step whose claim is
 * no longer the scanner's is answered 409, and the scanner drops what it found.
 */
@RestController
@RequestMapping("/internal/v1/scanner")
@ConditionalOnWebApplication
class ScannerController {

    static final String CLAIM = "Scanner-Claim";
    private static final String SHA256 = "sha256:[0-9a-f]{64}";

    private final ScannerQueue queue;

    ScannerController(ScannerQueue queue) {
        this.queue = queue;
    }

    record ClaimRequest(@NotBlank @Size(max = 100) String scanner) {}

    record ClaimResponse(
            UUID checkId,
            String claim,
            String reference,
            CheckedImages.@Nullable Login login,
            String platform,
            long maxLayerUnpackedBytes,
            long maxImageUnpackedBytes,
            long deadlineSeconds) {}

    record Resolution(
            @Nullable @Pattern(regexp = SHA256) String digest,
            @NotNull @Size(max = 64) List<@Size(max = 100) String> platforms,
            @Min(0) long compressedBytes,
            @Min(0) int layers) {}

    record Progress(
            @NotBlank @Pattern(regexp = "downloading|reading|scanning")
            String phase,

            @Min(0) long doneBytes,
            @Min(0) long totalBytes) {}

    record Proceed(boolean proceed) {}

    record Config(
            @Nullable @Size(max = 500) String cudaRequire,
            @Nullable @Size(max = 500) String cudaVersion,
            @NotNull @Size(max = 64) List<@Size(max = 4096) String> entrypoint,
            @NotNull @Size(max = 256) List<@Size(max = 4096) String> cmd,
            @Nullable @Size(max = 4096) String workingDir,
            @Nullable @Size(max = 256) String user) {}

    record Executable(
            @NotBlank @Size(max = 4096) String path,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256,
            @Min(0) int layer) {}

    record Secret(
            @NotBlank @Size(max = 4096) String path,
            @NotNull @Size(max = 200) String rule,
            @NotNull @Size(max = 200) String category,
            @NotNull @Size(max = 300) String title) {}

    record Findings(
            @NotNull @Size(max = 100) String trivyVersion,
            @Min(0) int critical,
            @Min(0) int high,
            @Min(0) int medium,
            @Min(0) int low,
            @NotNull @Size(max = 5000) List<@Valid VulnerabilityWire> vulnerabilities,
            @NotNull @Size(max = 1000) List<@Valid Secret> secrets) {}

    /** Trivy's names, as the scanner sends them. */
    record VulnerabilityWire(
            @NotBlank @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|UNKNOWN")
            String severity,

            @NotNull @Size(max = 200) String id,
            @NotNull @Size(max = 500) String packageName,
            @NotNull @Size(max = 500) String installed,
            @NotNull @Size(max = 500) String fixed,
            @NotNull @Size(max = 300) String title) {}

    record Result(
            @NotBlank @Pattern(regexp = SHA256) String digest,
            @Min(0) long compressedBytes,
            @Min(0) int layers,
            @NotNull @Valid Config config,
            @Min(0) long unpackedBytes,
            boolean hasShell,
            @NotNull @Size(max = 200_000) List<@Valid Executable> executables,
            @NotNull @Valid Findings findings) {}

    record Refusal(
            @NotBlank @Pattern(regexp = "pull_failed|unpack_failed|too_large_unpacked|unsafe_path")
            String reason,

            @NotBlank @Size(max = 2000) String detail) {}

    record Failure(@NotBlank @Size(max = 2000) String detail) {}

    @PostMapping("/claim")
    ResponseEntity<ClaimResponse> claim(@Valid @RequestBody ClaimRequest request) {
        return queue.claim(request.scanner())
                .map(claim -> ResponseEntity.ok(new ClaimResponse(
                        claim.checkId(),
                        claim.claim(),
                        claim.reference(),
                        claim.login(),
                        claim.platform(),
                        claim.maxLayerUnpackedBytes(),
                        claim.maxImageUnpackedBytes(),
                        claim.deadlineSeconds())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/checks/{id}/resolved")
    Proceed resolved(
            @PathVariable UUID id, @RequestHeader(CLAIM) String claim, @Valid @RequestBody Resolution resolution) {
        return new Proceed(queue.resolved(
                id,
                claim,
                resolution.digest(),
                resolution.platforms(),
                resolution.compressedBytes(),
                resolution.layers()));
    }

    @PostMapping("/checks/{id}/progress")
    Proceed progress(@PathVariable UUID id, @RequestHeader(CLAIM) String claim, @Valid @RequestBody Progress progress) {
        return new Proceed(queue.progress(id, claim, progress.phase(), progress.doneBytes(), progress.totalBytes()));
    }

    @PostMapping("/checks/{id}/result")
    ResponseEntity<Void> result(
            @PathVariable UUID id, @RequestHeader(CLAIM) String claim, @Valid @RequestBody Result result) {
        var findings = result.findings();
        queue.result(
                id,
                claim,
                new ScannerQueue.Facts(
                        result.digest(),
                        result.compressedBytes(),
                        result.layers(),
                        result.config().cudaRequire(),
                        result.config().cudaVersion(),
                        result.config().entrypoint(),
                        result.config().cmd(),
                        result.config().workingDir(),
                        result.config().user(),
                        result.unpackedBytes(),
                        result.hasShell(),
                        result.executables().stream()
                                .map(e -> new ScanStore.Executable(e.sha256(), e.path(), e.layer()))
                                .toList(),
                        findings.critical(),
                        findings.high(),
                        findings.medium(),
                        findings.low(),
                        findings.vulnerabilities().stream()
                                .map(v -> new ScanStore.Vulnerability(
                                        v.severity(), v.id(), v.packageName(), v.installed(), v.fixed(), v.title()))
                                .toList(),
                        findings.secrets().stream()
                                .map(s -> new ScanStore.Secret(s.path(), s.rule(), s.category(), s.title()))
                                .toList(),
                        findings.trivyVersion()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/checks/{id}/refused")
    ResponseEntity<Void> refused(
            @PathVariable UUID id, @RequestHeader(CLAIM) String claim, @Valid @RequestBody Refusal refusal) {
        queue.refused(id, claim, refusal.reason(), refusal.detail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/checks/{id}/failed")
    ResponseEntity<Void> failed(
            @PathVariable UUID id, @RequestHeader(CLAIM) String claim, @Valid @RequestBody Failure failure) {
        queue.failed(id, claim, failure.detail());
        return ResponseEntity.noContent().build();
    }

    /** The claim is someone else's now, or the check is over: the scanner drops what it has. */
    @ExceptionHandler(ScannerQueue.ClaimLost.class)
    ResponseEntity<Void> lost() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
