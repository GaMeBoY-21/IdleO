package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staged rollouts (ADR 0056): each release's share of hosts and how its hosts are doing against the rest. Pausing
 * is immediate; widening is a request another admin approves, and with no one else to, it runs at once, as running a
 * kill switch again does.
 */
@RestController
@ConditionalOnWebApplication
class AdminRolloutsController {

    static final String WIDEN = "rollout.widen";

    private final AgentRollouts rollouts;
    private final AgentReleaseFeed feed;
    private final Approvals approvals;
    private final AuditTrail audit;

    AdminRolloutsController(AgentRollouts rollouts, AgentReleaseFeed feed, Approvals approvals, AuditTrail audit) {
        this.rollouts = rollouts;
        this.feed = feed;
        this.approvals = approvals;
        this.audit = audit;
    }

    /**
     * One release's rollout.
     *
     * @param id the release's version
     * @param signedPercent the most its signed manifest allows, when it's the release the feed holds
     * @param failureRate the share of its hosts' sessions lost over the last 24 hours
     */
    record AdminRollout(
            String id,
            int percent,
            @Nullable Integer signedPercent,
            boolean paused,
            String reason,
            @Nullable UUID changedBy,
            Instant changedAt,
            int hosts,
            long sessions,
            double failureRate,
            double reconnectsPerHostDay,
            int restHosts,
            double restFailureRate,
            double restReconnectsPerHostDay,
            long version) {}

    record AdminRollouts(List<AdminRollout> items) {}

    record WidenRequest(
            int percent,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {

        WidenRequest {
            reason = reason == null ? null : reason.strip();
        }
    }

    @RequiresPermission(AdminPermission.FLEET_READ)
    @GetMapping("/admin/v1/rollouts")
    AdminRollouts list() {
        var latest = feed.latest().orElse(null);
        return new AdminRollouts(rollouts.all().stream()
                .map(rollout -> {
                    var health = rollouts.health(rollout.release());
                    return new AdminRollout(
                            rollout.release(),
                            rollout.percent(),
                            latest != null && latest.version().equals(rollout.release())
                                    ? latest.rolloutPercent()
                                    : null,
                            rollout.paused(),
                            rollout.reason(),
                            rollout.changedBy(),
                            rollout.changedAt(),
                            health.hosts(),
                            health.sessions(),
                            com.idleo.controlplane.ControlRoom.round(health.failureRate()),
                            health.reconnectsPerHostDay(),
                            health.restHosts(),
                            com.idleo.controlplane.ControlRoom.round(health.restFailureRate()),
                            health.restReconnectsPerHostDay(),
                            rollout.version());
                })
                .toList());
    }

    /** Pauses it now: no host is offered it. */
    @RequiresPermission(value = AdminPermission.ROLLOUTS_PAUSE, record = "rollout")
    @PostMapping("/admin/v1/rollouts/{id}/pause")
    AdminRollouts pause(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable String id,
            @Valid @RequestBody AdminReason request) {
        rollouts.pause(id, request.reason(), audit.admin(admin));
        return list();
    }

    /** Asks to widen it, to 5, 25, 50 or 100% of hosts, and run it again if it's paused: 202 with the request. */
    @RequiresPermission(value = AdminPermission.ROLLOUTS_WIDEN, record = "rollout")
    @PostMapping("/admin/v1/rollouts/{id}/widen")
    ResponseEntity<Approvals.AdminApproval> widen(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable String id,
            @Valid @RequestBody WidenRequest request) {
        if (!AgentRollouts.STEPS.contains(request.percent())) {
            throw ApiProblem.invalid("percent", "is 5, 25, 50 or 100.");
        }
        var rollout = rollouts.find(id)
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no rollout of that release."));
        if (request.percent() < rollout.percent()) {
            throw ApiProblem.of(
                    Problem.ACTION_REFUSED,
                    "A rollout only widens: pause it to stop offering it, rather than narrowing it.");
        }
        return approvals.submit(
                admin,
                WIDEN,
                "rollout",
                id,
                Map.of("id", id, "percent", request.percent()),
                List.of(new Approvals.ApprovalChange(
                        "hosts offered " + id,
                        rollout.paused() ? "paused" : rollout.percent() + "%",
                        request.percent() + "%")),
                request.reason());
    }
}
