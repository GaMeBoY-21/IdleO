package com.idleo.controlplane.flags.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Flag;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's flags (ADR 0056). Pausing is immediate, with a factor and a reason; running a flag again is a request
 * another admin approves, and with no one else to approve it, it runs at once: a kill switch never waits out a
 * cooling-off delay, because an outage must not last an hour longer than it has to.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/admin/v1/flags")
class AdminFlagsController {

    static final String RESUME = "flag.resume";

    private final FlagStore flags;
    private final Approvals approvals;
    private final AuditTrail audit;

    AdminFlagsController(FlagStore flags, Approvals approvals, AuditTrail audit) {
        this.flags = flags;
        this.approvals = approvals;
        this.audit = audit;
    }

    /**
     * One flag.
     *
     * @param id its key, such as {@code bookings.new}
     * @param owner who decides about it
     * @param pausedIfUnknown its safe value when the flags can't be read
     * @param emailsOwners whether a change emails the Owners
     * @param until when it's expected to run again, if said
     * @param resumeAutomatically whether it runs again by itself at {@code until}
     * @param reason the last change's reason
     * @param changedBy the admin who made the last change; null for the system or never
     */
    record AdminFlag(
            String id,
            String owner,
            String description,
            boolean pausedIfUnknown,
            boolean emailsOwners,
            boolean paused,
            @Nullable Instant until,
            boolean resumeAutomatically,
            @Nullable String reason,
            @Nullable UUID changedBy,
            @Nullable Instant changedAt,
            long version) {}

    record AdminFlags(List<AdminFlag> items) {}

    /**
     * A pause.
     *
     * @param until when it's expected back, for the banner and Retry-After; optional
     * @param resumeAutomatically run again by itself at {@code until} ("off for 2 h")
     */
    record FlagPauseRequest(
            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason,

            @Nullable Instant until,
            @Nullable Boolean resumeAutomatically) {

        FlagPauseRequest {
            reason = reason == null ? null : reason.strip();
        }
    }

    @RequiresPermission(AdminPermission.FLAGS_READ)
    @GetMapping
    AdminFlags list() {
        return new AdminFlags(
                flags.stored().stream().map(AdminFlagsController::view).toList());
    }

    /** Pauses it now; pausing a paused one changes its time and reason. */
    @RequiresPermission(value = AdminPermission.FLAGS_CHANGE, record = "flag")
    @PostMapping("/{id}/pause")
    AdminFlag pause(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable String id,
            @Valid @RequestBody FlagPauseRequest request) {
        var flag = flag(id);
        return view(flags.pause(
                flag,
                request.until(),
                Boolean.TRUE.equals(request.resumeAutomatically()),
                request.reason(),
                audit.admin(admin)));
    }

    /** Asks to run it again: 202 with the request, which has already run when no one else could approve it. */
    @RequiresPermission(value = AdminPermission.FLAGS_CHANGE, record = "flag")
    @PostMapping("/{id}/resume")
    ResponseEntity<Approvals.AdminApproval> resume(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable String id,
            @Valid @RequestBody com.idleo.controlplane.AdminReason request) {
        var flag = flag(id);
        var stored = flags.stored(flag).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such flag."));
        if (!stored.paused()) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, flag.key() + " is running already.");
        }
        return approvals.submit(
                admin,
                RESUME,
                "flag",
                flag.key(),
                Map.of("id", flag.key()),
                List.of(new Approvals.ApprovalChange(flag.key(), "paused", "running")),
                request.reason());
    }

    private static Flag flag(String id) {
        return Flag.of(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such flag."));
    }

    private static AdminFlag view(FlagStore.Stored stored) {
        var flag = stored.flag();
        return new AdminFlag(
                flag.key(),
                flag.owner(),
                flag.description(),
                flag.pausedIfUnknown(),
                flag.emailsOwners(),
                stored.paused(),
                stored.until(),
                stored.resumeAutomatically(),
                stored.reason(),
                stored.changedBy(),
                stored.changedAt(),
                stored.version());
    }
}
