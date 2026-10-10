package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.hosts.HostListings;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's hosts. See the hosts module's package documentation. */
@RestController
@RequestMapping("/v1/hosts")
class HostController {

    static final String IDEMPOTENCY_TABLE = "hosts.idempotency_keys";

    private final HostService hosts;
    private final HostListingService listings;
    private final HostViews views;
    private final IdempotentRequests idempotency;

    HostController(HostService hosts, HostListingService listings, HostViews views, IdempotentRequests idempotency) {
        this.hosts = hosts;
        this.listings = listings;
        this.views = views;
        this.idempotency = idempotency;
    }

    /**
     * A host's listing (ADR 0015). One session at a time for now: one job per GPU host keeps isolation and
     * billing simple.
     */
    record ListingRequest(
            @NotNull @Min(1) Integer batchPricePaisePerMin,
            @NotNull @Min(60) @Max(95) Integer gpuTempCapC,
            @NotNull @Min(1) @Max(1) Integer maxConcurrentSessions,

            @Min(HostListings.Listing.MIN_KEEP_FREE_GB) @Max(HostListings.Listing.MAX_KEEP_FREE_GB) @Nullable
            Integer keepFreeGb) {}

    @PutMapping("/{id}/listing")
    HostListings.Listing putListing(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ListingRequest request) {
        views.owned(user.id(), id);
        return listings.put(
                        user.id(),
                        id,
                        new HostListings.Listing(
                                request.batchPricePaisePerMin(),
                                request.gpuTempCapC(),
                                request.maxConcurrentSessions(),
                                request.keepFreeGb() == null
                                        ? HostListings.Listing.DEFAULT_KEEP_FREE_GB
                                        : request.keepFreeGb()))
                .orElseThrow(HostViews::notFound);
    }

    record CreateHostRequest(@NotBlank @Size(max = 100) String name) {}

    record HostList(List<HostViews.HostResponse> hosts) {}

    record EnrolmentCodeResponse(String code, Instant expiresAt) {}

    @PostMapping
    @ApiResponse(
            responseCode = "201",
            description = "The new host; a repeat with the same key returns the first response",
            headers = @Header(name = "Location", description = "The host's URL", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = HostViews.HostResponse.class)))
    ResponseEntity<String> create(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody CreateHostRequest request) {
        return idempotency.execute(IDEMPOTENCY_TABLE, user.id(), idempotencyKey, request, () -> {
            var host = hosts.create(user.id(), request.name().strip());
            return ResponseEntity.created(URI.create("/v1/hosts/" + host.id())).body(views.view(host));
        });
    }

    @GetMapping
    HostList list(@AuthenticationPrincipal CurrentUser user) {
        return new HostList(views.views(hosts.list(user.id())));
    }

    @GetMapping("/{id}")
    HostViews.HostResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return views.view(views.owned(user.id(), id));
    }

    /** The code is shown once and never stored, so the response must not be cached either. */
    @PostMapping("/{id}/enrolment-code")
    @ApiResponse(
            responseCode = "201",
            description = "A one-time code, shown once and never stored",
            content = @Content(schema = @Schema(implementation = EnrolmentCodeResponse.class)))
    ResponseEntity<EnrolmentCodeResponse> issueEnrolmentCode(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        views.owned(user.id(), id);
        var issued = hosts.issueCode(user.id(), id).orElseThrow(HostViews::notFound);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new EnrolmentCodeResponse(issued.code(), issued.expiresAt()));
    }
}
