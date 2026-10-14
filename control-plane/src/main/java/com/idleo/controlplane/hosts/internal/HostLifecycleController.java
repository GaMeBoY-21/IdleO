package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.identity.BrowserSignInOnly;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pausing, renaming and removing the signed-in user's hosts (ADR 0023). Another user's host is a 404; a host they
 * removed is a 410 {@code host-removed}.
 */
@RestController
@RequestMapping("/v1/hosts/{id}")
class HostLifecycleController {

    private final HostLifecycle lifecycle;
    private final HostViews views;

    HostLifecycleController(HostLifecycle lifecycle, HostViews views) {
        this.lifecycle = lifecycle;
        this.views = views;
    }

    /** {@code until} is a time within 7 days from now; leave it out to pause until the owner resumes. */
    record PauseRequest(@Nullable Instant until) {}

    record RenameRequest(@NotBlank @Size(max = 100) String name) {}

    /** A paused host gets no new offers; a session it's running finishes. Pausing again replaces the pause. */
    @PostMapping("/pause")
    HostViews.HostResponse pause(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) @Nullable PauseRequest request) {
        return views.view(lifecycle
                .pause(user.id(), id, request == null ? null : request.until())
                .orElseThrow(HostViews::notFound));
    }

    @PostMapping("/resume")
    HostViews.HostResponse resume(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return views.view(lifecycle.resume(user.id(), id).orElseThrow(HostViews::notFound));
    }

    @PatchMapping
    HostViews.HostResponse rename(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID id,
            @Valid @RequestBody RenameRequest request) {
        return views.view(
                lifecycle.rename(user.id(), id, request.name().strip()).orElseThrow(HostViews::notFound));
    }

    /** Refused with 409 {@code host-busy} while the host runs a session. */
    @BrowserSignInOnly
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        lifecycle.remove(user.id(), id).orElseThrow(HostViews::notFound);
    }
}
