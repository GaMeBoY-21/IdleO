package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Windows app's latest release (ADR 0034), with no sign-in and limited per address like every signed-out call:
 * the setup app downloads from here, and so do the website's and the web app's Download buttons. With no release, a
 * {@code no-agent-release} problem, and the buttons keep saying the app is coming.
 */
@RestController
@RequestMapping("/v1/agent-releases/latest")
class AgentReleaseController {

    private static final CacheControl ONE_MINUTE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final AgentReleaseFeed feed;

    AgentReleaseController(AgentReleaseFeed feed) {
        this.feed = feed;
    }

    /** Links to a release's three files, valid for an hour. Not signed: the manifest's size and SHA-256 are. */
    record ReleaseUrls(String app, String distro, String setup) {}

    /**
     * The latest release.
     *
     * @param version the manifest's version, for display (the signed manifest is the truth)
     * @param setupSizeBytes the setup program's size, for the Download button
     * @param manifest the manifest exactly as signed, UTF-8 JSON; verify {@code signature} over its bytes
     * @param signature the Ed25519 signature over {@code manifest}, base64
     */
    record LatestRelease(String version, long setupSizeBytes, String manifest, String signature, ReleaseUrls urls) {}

    @GetMapping
    ResponseEntity<LatestRelease> latest() {
        var manifest = feed.latest().orElseThrow(AgentReleaseController::none);
        var links = feed.links(manifest);
        return ResponseEntity.ok()
                .cacheControl(ONE_MINUTE)
                .body(new LatestRelease(
                        manifest.version(),
                        manifest.setup().size(),
                        new String(manifest.bytes(), StandardCharsets.UTF_8),
                        Base64.getEncoder().encodeToString(manifest.signature()),
                        new ReleaseUrls(links.app(), links.distro(), links.setup())));
    }

    /** The setup program itself: a redirect to its download link, which saves it as {@code IdleO-Setup.exe}. */
    @GetMapping("/setup")
    @ApiResponse(
            responseCode = "302",
            description = "The setup program's download link, valid for an hour",
            headers = @Header(name = "Location", description = "Where to download IdleO-Setup.exe"))
    ResponseEntity<Void> setup() {
        var manifest = feed.latest().orElseThrow(AgentReleaseController::none);
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(ONE_MINUTE)
                .location(URI.create(feed.links(manifest).setup()))
                .build();
    }

    private static ApiProblem none() {
        return ApiProblem.of(Problem.NO_AGENT_RELEASE, "There's no release of the Windows app yet.");
    }
}
