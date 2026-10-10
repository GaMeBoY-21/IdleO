package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.hosts.HostTelemetry;
import com.idleo.controlplane.identity.CurrentUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** What a machine's GPUs are doing now and have done in the last hour, for its owner's screen (ADR 0023). */
@RestController
class HostTelemetryController {

    private final HostViews views;
    private final HostTelemetry telemetry;

    HostTelemetryController(HostViews views, HostTelemetry telemetry) {
        this.views = views;
        this.telemetry = telemetry;
    }

    /**
     * {@code now} is the latest heartbeat, empty while the agent is offline; {@code lastHour} is up to 60 points, one a
     * minute, oldest first. For the screen only: never used for billing.
     */
    @GetMapping("/v1/hosts/{id}/telemetry")
    HostTelemetry.Telemetry get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        views.owned(user.id(), id);
        return telemetry.of(id);
    }
}
