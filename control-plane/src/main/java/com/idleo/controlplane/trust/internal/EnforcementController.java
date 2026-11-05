package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.OpenToHeldAccounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The ladder and its appeals (ADR 0053): for admins under {@code /admin/}, and for the person under {@code /v1/}. */
@RestController
@ConditionalOnWebApplication
class EnforcementController {

    static final String LIFT = "enforcement.lift";
    static final String REVERSE = "appeal.reverse";

    private final Enforcements enforcements;
    private final Appeals appeals;
    private final Approvals approvals;
    private final AuditTrail audit;
    private final JdbcClient jdbc;

    EnforcementController(
            Enforcements enforcements, Appeals appeals, Approvals approvals, AuditTrail audit, JdbcClient jdbc) {
        this.enforcements = enforcements;
        this.appeals = appeals;
        this.approvals = approvals;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    record AdminEnforcement(
            UUID id,
            UUID userId,
            String role,
            String step,
            String template,
            String title,
            String clause,
            @Nullable String limitKind,
            @Nullable Integer limitHours,
            @Nullable Instant endsAt,
            Instant strikeExpiresAt,
            String state,
            @Nullable String reason,
            UUID enforcedBy,
            Instant createdAt,
            boolean appealed,
            long version) {

        static AdminEnforcement of(Enforcements.Enforcement step) {
            return new AdminEnforcement(
                    step.id(),
                    step.userId(),
                    step.role(),
                    step.step(),
                    step.template(),
                    step.title(),
                    step.clause(),
                    step.limitKind(),
                    step.limitHours(),
                    step.endsAt(),
                    step.strikeExpiresAt(),
                    step.state(),
                    step.reason(),
                    step.enforcedBy(),
                    step.createdAt(),
                    step.appealed(),
                    step.version());
        }
    }

    record AdminEnforcementPage(
            List<AdminEnforcement> enforcements,
            @Nullable Long renterStrikes,
            @Nullable Long hostStrikes,
            @Nullable String nextCursor) {}

    /** What's been done, newest first. With {@code user}, that person's, and the strikes that still count. */
    @RequiresPermission(AdminPermission.ENFORCEMENT_READ)
    @AdminList("enforcements")
    @GetMapping("/admin/v1/enforcements")
    AdminEnforcementPage list(
            @RequestParam(required = false) @Nullable UUID user,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String step,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = Enforcements.LIST.page(
                jdbc,
                AdminListing.Asked.of(sort, "user", user, "state", state, "step", step),
                cursor,
                limit,
                Enforcements::row);
        return new AdminEnforcementPage(
                page.rows().stream().map(AdminEnforcement::of).toList(),
                user == null ? null : enforcements.strikes(user, "renter"),
                user == null ? null : enforcements.strikes(user, "host"),
                page.nextCursor());
    }

    record ApplyRequest(
            @NotNull UUID userId,
            @NotBlank @Size(max = 40) String template,
            @Nullable String limitKind,
            @Nullable Integer limitHours,
            @Nullable Instant endsAt,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /** Takes the step at once, and tells the person why, with a link to appeal. Stopping is never gated. */
    @RequiresPermission(AdminPermission.ENFORCEMENT_APPLY)
    @PostMapping("/admin/v1/enforcements")
    AdminEnforcement apply(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody ApplyRequest request) {
        return AdminEnforcement.of(enforcements.apply(
                new Enforcements.Asked(
                        request.userId(),
                        request.template(),
                        request.limitKind(),
                        request.limitHours(),
                        request.endsAt(),
                        request.reason()),
                audit.admin(admin)));
    }

    /** Asks to lift it: another admin approves. */
    @RequiresPermission(value = AdminPermission.ENFORCEMENT_LIFT, record = "enforcement")
    @PostMapping("/admin/v1/enforcements/{id}/lift")
    ResponseEntity<Approvals.AdminApproval> lift(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        var step = enforcements.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such step."));
        return approvals.submit(
                admin,
                LIFT,
                "enforcement",
                id.toString(),
                Map.of("id", id),
                List.of(new Approvals.ApprovalChange(
                        step.step() + " on user " + step.userId(), step.state(), "lifted")),
                request.reason());
    }

    @Bean
    Approvals.GatedAction liftEnforcement() {
        return Approvals.action(
                LIFT,
                AdminPermission.ENFORCEMENT_LIFT,
                Approvals.Cooling.ACCESS,
                run -> enforcements.lift(
                        UUID.fromString(run.params().get("id").asString()), run.reason(), run.actor()));
    }

    @Bean
    Approvals.GatedAction reverseAppeal() {
        return Approvals.action(
                REVERSE,
                AdminPermission.ENFORCEMENT_LIFT,
                Approvals.Cooling.ACCESS,
                run -> appeals.reversed(UUID.fromString(run.params().get("id").asString()), run.reason(), run.actor()));
    }

    record AdminAppeal(
            UUID id,
            UUID enforcementId,
            UUID userId,
            @Nullable String body,
            List<UUID> attachments,
            String state,
            @Nullable UUID decidedBy,
            @Nullable String decisionReason,
            boolean sameReviewer,
            Instant createdAt,
            @Nullable Instant decidedAt,
            long version) {

        static AdminAppeal of(Appeals.Appeal appeal) {
            return new AdminAppeal(
                    appeal.id(),
                    appeal.enforcementId(),
                    appeal.userId(),
                    appeal.body(),
                    appeal.attachments(),
                    appeal.state(),
                    appeal.decidedBy(),
                    appeal.decisionReason(),
                    appeal.sameReviewer(),
                    appeal.createdAt(),
                    appeal.decidedAt(),
                    appeal.version());
        }
    }

    record AdminAppealPage(
            List<AdminAppeal> appeals, @Nullable String nextCursor) {}

    @RequiresPermission(AdminPermission.ENFORCEMENT_READ)
    @AdminList("appeals")
    @GetMapping("/admin/v1/appeals")
    AdminAppealPage appeals(
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = Appeals.LIST.page(jdbc, AdminListing.Asked.of(sort, "state", state), cursor, limit, Appeals::row);
        return new AdminAppealPage(page.rows().stream().map(AdminAppeal::of).toList(), page.nextCursor());
    }

    /** An appeal, with the step it's about and everything else on the account: what its reviewer reads. */
    record AdminAppealRecord(AdminAppeal appeal, AdminEnforcement enforcement, List<AdminEnforcement> history) {}

    @RequiresPermission(AdminPermission.ENFORCEMENT_READ)
    @GetMapping("/admin/v1/appeals/{id}")
    AdminAppealRecord appeal(@PathVariable UUID id) {
        var appeal = appeals.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such appeal."));
        return new AdminAppealRecord(
                AdminAppeal.of(appeal),
                AdminEnforcement.of(enforcements.find(appeal.enforcementId()).orElseThrow()),
                enforcements.of(appeal.userId()).stream()
                        .map(AdminEnforcement::of)
                        .toList());
    }

    /** {@code outcome}: uphold, or reverse, whose lift another admin approves. */
    record AppealDecision(
            @NotBlank String outcome,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /** Decides it. Refused for whoever took the step, unless no one else can decide; then {@code sameReviewer} says so. */
    @RequiresPermission(value = AdminPermission.APPEALS_DECIDE, record = "appeal")
    @PostMapping("/admin/v1/appeals/{id}/decide")
    ResponseEntity<?> decide(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AppealDecision request) {
        var actor = audit.admin(admin);
        return switch (request.outcome()) {
            case "uphold" -> ResponseEntity.ok(AdminAppeal.of(appeals.uphold(id, request.reason(), actor)));
            case "reverse" -> {
                var appeal = appeals.askToReverse(id, actor);
                yield approvals.submit(
                        admin,
                        REVERSE,
                        "appeal",
                        id.toString(),
                        Map.of("id", id),
                        List.of(new Approvals.ApprovalChange(
                                "the step on user " + appeal.userId(), "active", "lifted, and the person told")),
                        request.reason());
            }
            default -> throw ApiProblem.invalid("outcome", "must be uphold or reverse.");
        };
    }

    // ---- The person's own.

    /** A step, as the person reads it. */
    record MyEnforcement(
            UUID id,
            String step,
            String title,
            String clause,
            @Nullable String reason,
            @Nullable Instant endsAt,
            String state,
            Instant createdAt,
            @Nullable String appeal,
            @Nullable String appealOutcome) {}

    record MyEnforcements(List<MyEnforcement> enforcements) {}

    /** What's been decided about this account, and how its appeals went. A held account can read it. */
    @OpenToHeldAccounts
    @GetMapping("/v1/me/enforcements")
    MyEnforcements mine(@AuthenticationPrincipal CurrentUser user) {
        return new MyEnforcements(enforcements.of(user.id()).stream()
                .map(step -> {
                    var appeal = appeals.ofEnforcement(step.id()).orElse(null);
                    return new MyEnforcement(
                            step.id(),
                            step.step(),
                            step.title(),
                            step.clause(),
                            step.reason(),
                            step.endsAt(),
                            step.state(),
                            step.createdAt(),
                            appeal == null ? null : appeal.state(),
                            appeal == null ? null : appeal.decisionReason());
                })
                .toList());
    }

    record NewAppeal(
            @NotBlank @Size(max = 5000) String body,
            @Size(max = 3) @Nullable List<UUID> attachments) {}

    /** Appeals a step, once: a few words, and up to 3 of the person's own uploads. */
    @OpenToHeldAccounts
    @PostMapping("/v1/me/enforcements/{id}/appeal")
    MyEnforcement appeal(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID id, @Valid @RequestBody NewAppeal request) {
        appeals.make(user.id(), id, request.body(), request.attachments() == null ? List.of() : request.attachments());
        return mine(user).enforcements().stream()
                .filter(step -> step.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
