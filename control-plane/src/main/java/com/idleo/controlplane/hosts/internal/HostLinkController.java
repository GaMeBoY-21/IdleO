package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostListings;
import com.idleo.controlplane.identity.BrowserSignInOnly;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Linking a host by approval (ADR 0032). Asking to be linked and polling for the ticket need no sign-in (the poll
 * token is the agent's own proof); reading a link, approving it and denying it need a browser's sign-in (KeyRules).
 * A link that is unknown, or polled with the wrong token, is a 404.
 */
@RestController
@RequestMapping("/v1/host-links")
class HostLinkController {

    private static final int DEFAULT_TEMP_CAP_C = 85;

    private final HostLinks links;

    HostLinkController(HostLinks links) {
        this.links = links;
    }

    record GpuInfo(
            @NotBlank @Size(max = 100) String model,
            @NotNull @Min(1) @Max(1_000_000) Integer vramMb) {}

    /** What the agent knows of its machine. None of it is trusted: it is what the approval page shows. */
    record StartLinkRequest(
            @NotBlank @Size(max = 100) String machineName,
            @NotBlank @Size(max = 40) String os,
            @NotBlank @Size(max = 40) String agentVersion,
            @NotNull @Size(max = 8) List<@Valid GpuInfo> gpus) {}

    /** What the agent keeps: the link to open, and the token to poll with, which is never shown to the person. */
    record StartedLink(UUID id, String approveUrl, String pollToken, long intervalSeconds, long expiresInSeconds) {}

    record LinkResponse(
            UUID id,
            String machineName,
            String os,
            String agentVersion,
            List<GpuInfo> gpus,
            String state,
            Instant expiresAt,
            @Nullable UUID hostId,
            boolean awaitsActivation) {}

    /** What the owner confirms: the name, and the listing the machine starts with. */
    record ApproveLinkRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Min(1) Integer batchPricePaisePerMin,
            @Nullable @Min(60) @Max(95) Integer gpuTempCapC,

            @Nullable @Min(HostListings.Listing.MIN_KEEP_FREE_GB) @Max(HostListings.Listing.MAX_KEEP_FREE_GB)
            Integer keepFreeGb) {}

    record TicketRequest(@NotBlank @Size(max = 128) String pollToken) {}

    /** The one-time code that lets the agent trade its key for a certificate, shown to it this once. */
    record Ticket(
            UUID hostId,
            String hostName,
            String enrolmentCode,
            Instant codeExpiresAt,
            @Nullable String ownerHint) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StartedLink start(@Valid @RequestBody StartLinkRequest request) {
        var machine = new HostLinks.Machine(
                request.machineName().strip(),
                request.os().strip(),
                request.agentVersion().strip(),
                request.gpus().stream()
                        .map(gpu -> new HostLinks.Gpu(gpu.model().strip(), gpu.vramMb()))
                        .toList());
        var started = links.start(machine);
        var url = UriComponentsBuilder.fromUri(links.properties().link().webAppUrl())
                .path("/link/{id}/")
                .buildAndExpand(started.id())
                .toUriString();
        return new StartedLink(
                started.id(),
                url,
                started.pollToken(),
                links.properties().link().pollInterval().toSeconds(),
                links.properties().link().ttl().toSeconds());
    }

    /** The approval page's lookup. Never returns the poll token. */
    @BrowserSignInOnly
    @GetMapping("/{id}")
    LinkResponse get(@PathVariable UUID id) {
        return links.find(id).map(this::view).orElseThrow(HostLinkController::notFound);
    }

    @BrowserSignInOnly
    @PostMapping("/{id}/approve")
    LinkResponse approve(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ApproveLinkRequest request) {
        var terms = new HostLinks.Terms(
                request.name().strip(),
                request.batchPricePaisePerMin(),
                request.gpuTempCapC() == null ? DEFAULT_TEMP_CAP_C : request.gpuTempCapC(),
                request.keepFreeGb());
        return switch (links.approve(id, user.id(), terms)) {
            case HostLinks.Approval.Approved approved -> view(approved.link());
            case HostLinks.Approval.Denied _ -> throw denied();
            case HostLinks.Approval.Expired _ -> throw expired();
            case HostLinks.Approval.NotFound _ -> throw notFound();
        };
    }

    @BrowserSignInOnly
    @PostMapping("/{id}/deny")
    LinkResponse deny(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return switch (links.deny(id, user.id())) {
            case HostLinks.Denial.Done done -> view(done.link());
            case HostLinks.Denial.AlreadyApproved _ ->
                throw ApiProblem.of(
                        Problem.HOST_LINK_APPROVED,
                        "This laptop was already linked. To take it back, remove it from Hosting.");
            case HostLinks.Denial.Expired _ -> throw expired();
            case HostLinks.Denial.NotFound _ -> throw notFound();
        };
    }

    /**
     * The agent's poll. Until the link is approved, 400 {@code authorization-pending}; once it is, the host's enrolment
     * code, this once; after that, or once the link expires, 400 {@code host-link-expired}; if it was denied, 400
     * {@code host-link-denied}. Never cached.
     */
    @PostMapping("/{id}/ticket")
    ResponseEntity<Ticket> ticket(@PathVariable UUID id, @Valid @RequestBody TicketRequest request) {
        return switch (links.redeem(id, request.pollToken())) {
            case HostLinks.Redemption.Handed ticket ->
                ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new Ticket(
                                ticket.hostId(),
                                ticket.hostName(),
                                ticket.enrolmentCode(),
                                ticket.codeExpiresAt(),
                                ticket.ownerHint()));
            case HostLinks.Redemption.WaitingForApproval _ ->
                throw ApiProblem.of(
                        Problem.AUTHORIZATION_PENDING,
                        "Not approved yet. Open the link in a browser, sign in and approve this laptop.");
            case HostLinks.Redemption.Denied _ -> throw denied();
            case HostLinks.Redemption.Expired _ -> throw expired();
            case HostLinks.Redemption.NotFound _ -> throw notFound();
        };
    }

    private LinkResponse view(HostLinks.Link link) {
        return new LinkResponse(
                link.id(),
                link.machine().name(),
                link.machine().os(),
                link.machine().agentVersion(),
                link.machine().gpus().stream()
                        .map(gpu -> new GpuInfo(gpu.model(), gpu.vramMb()))
                        .toList(),
                link.state().name().toLowerCase(java.util.Locale.ROOT),
                link.expiresAt(),
                link.hostId(),
                !links.properties().autoActivate());
    }

    private static ApiProblem denied() {
        return ApiProblem.of(Problem.HOST_LINK_DENIED, "This link was denied: the person said it isn't their laptop.");
    }

    private static ApiProblem expired() {
        return ApiProblem.of(Problem.HOST_LINK_EXPIRED, "This link has expired or was already used.");
    }

    private static ApiProblem notFound() {
        return ApiProblem.of(Problem.NOT_FOUND, "There's no such link.");
    }
}
