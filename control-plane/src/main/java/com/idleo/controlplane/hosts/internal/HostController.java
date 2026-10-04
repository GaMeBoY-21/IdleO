package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.IdempotentRequests;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in user's hosts. See the hosts module's package documentation. */
@RestController
@RequestMapping("/v1/hosts")
class HostController {

    static final String IDEMPOTENCY_TABLE = "hosts.idempotency_keys";

    private final HostService hosts;
    private final IdempotentRequests idempotency;

    HostController(HostService hosts, IdempotentRequests idempotency) {
        this.hosts = hosts;
        this.idempotency = idempotency;
    }

    record CreateHostRequest(@NotBlank @Size(max = 100) String name) {}

    record HostResponse(UUID id, String name, String status, Instant createdAt) {

        static HostResponse of(Host host) {
            return new HostResponse(host.id(), host.name(), host.status(), host.createdAt());
        }
    }

    record HostList(List<HostResponse> hosts) {}

    record EnrolmentCodeResponse(String code, Instant expiresAt) {}

    @PostMapping
    ResponseEntity<String> create(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(name = IdempotentRequests.HEADER, required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody CreateHostRequest request) {
        return idempotency.execute(IDEMPOTENCY_TABLE, user.id(), idempotencyKey, request, () -> {
            var host = hosts.create(user.id(), request.name().strip());
            return ResponseEntity.created(URI.create("/v1/hosts/" + host.id())).body(HostResponse.of(host));
        });
    }

    @GetMapping
    HostList list(@AuthenticationPrincipal CurrentUser user) {
        return new HostList(hosts.list(user.id()).stream().map(HostResponse::of).toList());
    }

    @GetMapping("/{id}")
    HostResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return hosts.find(user.id(), id).map(HostResponse::of).orElseThrow(HostController::notFound);
    }

    /** The code is shown once and never stored, so the response must not be cached either. */
    @PostMapping("/{id}/enrolment-code")
    ResponseEntity<EnrolmentCodeResponse> issueEnrolmentCode(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        var issued = hosts.issueCode(user.id(), id).orElseThrow(HostController::notFound);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new EnrolmentCodeResponse(issued.code(), issued.expiresAt()));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such host.");
    }
}
