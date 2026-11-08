package com.idleo.controlplane.queues.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The console's one inbox (ADR 0053): views, the sidebar's counts, claiming and assigning, priority, the targets, macros
 * and bulk actions. Every action takes an item's version in {@code If-Match} and an {@code Idempotency-Key} (ADR 0051).
 */
@RestController
@RequestMapping("/admin/v1/queue")
@ConditionalOnWebApplication
class AdminQueueController {

    private final QueueItems items;
    private final Macros macros;
    private final BulkActions bulk;
    private final Approvals approvals;
    private final AdminTagged tagged;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final QueueProperties properties;
    private final TransactionTemplate transactions;

    AdminQueueController(
            QueueItems items,
            Macros macros,
            BulkActions bulk,
            Approvals approvals,
            AdminTagged tagged,
            AuditTrail audit,
            JdbcClient jdbc,
            Clock clock,
            QueueProperties properties,
            PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.items = items;
        this.macros = macros;
        this.bulk = bulk;
        this.approvals = approvals;
        this.tagged = tagged;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * An item of the inbox.
     *
     * @param priority urgent, high, normal or low, from the sum or an admin's override
     * @param assigneeName who's on it, as other admins know them
     * @param paused whether its timer is stopped, waiting on the person
     */
    record AdminQueueItem(
            UUID id,
            String type,
            String title,
            String link,
            @Nullable String subjectKind,
            @Nullable UUID subjectId,
            @Nullable UUID personId,
            String state,
            @Nullable UUID assigneeId,
            @Nullable String assigneeName,
            String priority,
            boolean overridden,
            @Nullable String overrideReason,
            Instant openedAt,
            @Nullable Instant dueAt,
            boolean paused,
            @Nullable Instant breachedAt,
            long moneyPaise,
            boolean runningSession,
            long version) {}

    record AdminQueuePage(
            List<AdminQueueItem> items, @Nullable String nextCursor) {}

    record AdminQueueCounts(long mine, long unassigned, long breaching) {}

    private AdminQueueItem view(QueueItems.Row row, Map<UUID, String> names) {
        return new AdminQueueItem(
                row.id(),
                row.type(),
                row.title(),
                row.link(),
                row.subjectKind(),
                row.subjectId(),
                row.person(),
                row.state(),
                row.assignee(),
                row.assignee() == null ? null : names.getOrDefault(row.assignee(), "Another admin"),
                QueueTimers.label(row.rank()),
                row.override() != null,
                row.overrideReason(),
                row.openedAt(),
                row.dueAt(),
                row.paused(),
                row.breachedAt(),
                row.moneyPaise(),
                row.runningSession(),
                row.version());
    }

    private AdminQueueItem view(QueueItems.Row row) {
        return view(row, items.names());
    }

    /**
     * What this admin may see, by priority and then the soonest due.
     *
     * @param view mine, unassigned, breaching or all
     */
    @RequiresPermission(AdminPermission.QUEUES_READ)
    @AdminList("inbox")
    @GetMapping
    AdminQueuePage list(
            @AuthenticationPrincipal CurrentUser admin,
            @RequestParam(defaultValue = "all") String view,
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = items.page(admin.id(), view, type, state, tagged.subjects("user", tag), sort, cursor, limit);
        var names = items.names();
        return new AdminQueuePage(
                page.rows().stream().map(row -> view(row, names)).toList(), page.nextCursor());
    }

    /** What the sidebar shows. */
    @RequiresPermission(AdminPermission.QUEUES_READ)
    @GetMapping("/counts")
    AdminQueueCounts counts(@AuthenticationPrincipal CurrentUser admin) {
        var counts = items.counts(admin.id());
        return new AdminQueueCounts(counts.mine(), counts.unassigned(), counts.breaching());
    }

    @RequiresPermission(AdminPermission.QUEUES_READ)
    @GetMapping("/{id}")
    AdminQueueItem get(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        return view(items.visible(admin.id(), id));
    }

    /** Puts it on you. 409 {@code action-refused} names who has it, when someone does. */
    @RequiresPermission(value = AdminPermission.QUEUES_CLAIM, record = "queue_item")
    @PostMapping("/{id}/claim")
    AdminQueueItem claim(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        return view(items.claim(admin.id(), id));
    }

    @RequiresPermission(value = AdminPermission.QUEUES_CLAIM, record = "queue_item")
    @PostMapping("/{id}/release")
    AdminQueueItem release(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        return view(items.release(admin.id(), id));
    }

    record AssignRequest(
            @NotNull UUID assignee,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /** Puts it on another admin. Audited. */
    @RequiresPermission(value = AdminPermission.QUEUES_ASSIGN, record = "queue_item")
    @PostMapping("/{id}/assign")
    AdminQueueItem assign(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AssignRequest request) {
        return view(items.assign(admin.id(), id, request.assignee(), request.reason()));
    }

    /** {@code priority} null gives it back to the sum. */
    record PriorityRequest(
            @Nullable String priority,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /** Overrides its priority, with a reason. Audited. */
    @RequiresPermission(value = AdminPermission.QUEUES_CLAIM, record = "queue_item")
    @PostMapping("/{id}/priority")
    AdminQueueItem priority(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody PriorityRequest request) {
        return view(items.prioritise(admin.id(), id, request.priority(), request.reason()));
    }

    // ---- The targets.

    /** One target. Null minutes mean none: a chargeback runs to Razorpay's date. {@code legal} means a law sets it. */
    record AdminSlaTarget(
            String sla,
            @Nullable Integer firstMinutes,
            @Nullable Integer resolveMinutes,
            boolean pauses,
            boolean legal,
            long version) {}

    record AdminSlaTargets(List<AdminSlaTarget> targets) {}

    @RequiresPermission(AdminPermission.QUEUES_READ)
    @GetMapping("/sla")
    AdminSlaTargets targets() {
        return new AdminSlaTargets(jdbc.sql("select * from queues.sla_targets order by sla")
                .query((rs, n) -> new AdminSlaTarget(
                        rs.getString("sla"),
                        rs.getObject("first_minutes", Integer.class),
                        rs.getObject("resolve_minutes", Integer.class),
                        rs.getBoolean("pauses"),
                        rs.getBoolean("legal"),
                        rs.getLong("version")))
                .list());
    }

    record SlaChange(
            @Nullable Integer firstMinutes,
            @Nullable Integer resolveMinutes,
            boolean pauses,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /** Changes a target. New timers and running ones follow it on the next pass. */
    @RequiresPermission(value = AdminPermission.QUEUES_SLA, record = "sla_target")
    @PostMapping("/sla/{sla}")
    AdminSlaTargets setTarget(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable String sla,
            @Valid @RequestBody SlaChange request) {
        if ((request.firstMinutes() != null && request.firstMinutes() < 1)
                || (request.resolveMinutes() != null && request.resolveMinutes() < 1)) {
            throw ApiProblem.invalid("firstMinutes", "A target is at least a minute, or none.");
        }
        transactions.executeWithoutResult(status -> {
            var changed = jdbc.sql("""
                            update queues.sla_targets
                            set first_minutes = :first, resolve_minutes = :resolve, pauses = :pauses, updated_by = :admin,
                                updated_at = :now
                            where sla = :sla
                            """)
                    .param("first", request.firstMinutes())
                    .param("resolve", request.resolveMinutes())
                    .param("pauses", request.pauses())
                    .param("admin", admin.id())
                    .param("now", Timestamp.from(clock.instant()))
                    .param("sla", sla)
                    .update();
            if (changed == 0) {
                throw ApiProblem.of(Problem.NOT_FOUND, "There's no such target.");
            }
            audit.record(new AuditTrail.Entry(
                    audit.admin(admin),
                    "queue.sla",
                    "sla_target",
                    sla,
                    request.reason(),
                    null,
                    Map.of(
                            "firstMinutes", String.valueOf(request.firstMinutes()),
                            "resolveMinutes", String.valueOf(request.resolveMinutes()),
                            "pauses", request.pauses())));
        });
        return targets();
    }

    // ---- Macros.

    record AdminMacro(
            UUID id,
            String type,
            String name,
            int number,
            @Nullable String body,
            @Nullable String setStatus,
            @Nullable String addTag,
            @Nullable String setPriority,
            Instant writtenAt,
            long version) {}

    record AdminMacroPage(List<AdminMacro> macros, @Nullable String nextCursor) {}

    record MacroRequest(
            @Size(max = 40) @Nullable String type,
            @Size(max = 80) @Nullable String name,
            @Size(max = 5000) @Nullable String body,
            @Nullable String setStatus,
            @Size(max = 40) @Nullable String addTag,
            @Nullable String setPriority) {

        Macros.Words words() {
            return new Macros.Words(body, setStatus, addTag, setPriority);
        }
    }

    private static AdminMacro view(Macros.Macro macro) {
        return new AdminMacro(
                macro.id(),
                macro.type(),
                macro.name(),
                macro.number(),
                macro.body(),
                macro.setStatus(),
                macro.addTag(),
                macro.setPriority(),
                macro.writtenAt(),
                macro.version());
    }

    @RequiresPermission(AdminPermission.QUEUES_READ)
    @AdminList("macros")
    @GetMapping("/macros")
    AdminMacroPage macros(
            @RequestParam(required = false) @Nullable String type,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = macros.page(type, sort, cursor, limit);
        return new AdminMacroPage(
                page.rows().stream().map(AdminQueueController::view).toList(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.MACROS_MANAGE)
    @PostMapping("/macros")
    AdminMacro createMacro(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody MacroRequest request) {
        if (request.type() == null || request.name() == null || request.name().isBlank()) {
            throw ApiProblem.invalid("name", "A macro has a kind of work and a name.");
        }
        return view(macros.create(admin.id(), request.type(), request.name(), request.words()));
    }

    /** A new version. What was applied before stays audited as the version it was. */
    @RequiresPermission(value = AdminPermission.MACROS_MANAGE, record = "macro")
    @PostMapping("/macros/{id}")
    AdminMacro editMacro(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody MacroRequest request) {
        return view(macros.edit(admin.id(), id, request.words()));
    }

    @RequiresPermission(value = AdminPermission.MACROS_MANAGE, record = "macro")
    @PostMapping("/macros/{id}/retire")
    void retireMacro(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        macros.retire(admin.id(), id);
    }

    record MacroPreview(
            String body,
            @Nullable String setStatus,
            @Nullable String addTag,
            @Nullable String setPriority) {}

    /** The words as this item's person would read them. */
    @RequiresPermission(AdminPermission.QUEUES_CLAIM)
    @GetMapping("/{id}/macros/{macro}")
    MacroPreview previewMacro(
            @AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id, @PathVariable UUID macro) {
        var row = items.visible(admin.id(), id);
        var found = macros.find(macro);
        if (!found.type().equals(row.type())) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This macro is for another kind of work.");
        }
        return new MacroPreview(macros.render(found, row), found.setStatus(), found.addTag(), found.setPriority());
    }

    record ApplyMacro(@NotNull UUID macro) {}

    /** Applies it: the reply through the module's own path, then the changes. Audited with its version. */
    @RequiresPermission(value = AdminPermission.QUEUES_CLAIM, record = "queue_item")
    @PostMapping("/{id}/macro")
    AdminQueueItem applyMacro(
            @AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id, @Valid @RequestBody ApplyMacro request) {
        var row = items.visible(admin.id(), id);
        macros.apply(admin.id(), row, macros.find(request.macro()));
        return view(items.visible(admin.id(), id));
    }

    // ---- Bulk actions.

    /** {@code list} is the list the rows are from; none means the inbox. */
    record BulkRequest(
            @Nullable String list,
            @NotEmpty @Size(max = BulkActions.MOST_ITEMS) List<UUID> items,
            @NotNull @Valid BulkActions.BulkAsked action) {}

    private static String listOr(@Nullable String list) {
        return list == null || list.isBlank() ? BulkActions.INBOX : list;
    }

    record AdminBulkLists(List<BulkActions.BulkList> lists) {}

    /** Every list this admin can read, and what a bulk action may do from it, or why nothing may. */
    @RequiresPermission(AdminPermission.QUEUES_BULK)
    @GetMapping("/bulk/lists")
    AdminBulkLists bulkLists(@AuthenticationPrincipal CurrentUser admin) {
        return new AdminBulkLists(bulk.lists(admin.id()));
    }

    /** Exactly what each item will become, with the counts. It changes nothing. */
    @RequiresPermission(AdminPermission.QUEUES_BULK)
    @PostMapping("/bulk/preview")
    BulkActions.BulkPreview preview(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody BulkRequest request) {
        return bulk.preview(admin.id(), listOr(request.list()), request.items(), request.action());
    }

    record BulkRun(
            @Nullable String list,
            @NotEmpty @Size(max = BulkActions.MOST_ITEMS) List<UUID> items,
            @NotNull @Valid BulkActions.BulkAsked action,
            @NotBlank String confirm,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /** The batch as it stands. */
    record AdminBulkAction(
            UUID id,
            BulkActions.BulkAsked action,
            String state,
            int total,
            int done,
            int skipped,
            int failed,
            int undone,
            boolean undoable,
            @Nullable Instant undoUntil,
            @Nullable Instant undoneAt,
            Instant createdAt,
            List<BulkActions.BulkOutcome> outcomes) {}

    private AdminBulkAction view(BulkActions.Batch batch) {
        return new AdminBulkAction(
                batch.id(),
                batch.action(),
                batch.state(),
                batch.total(),
                batch.done(),
                batch.skipped(),
                batch.failed(),
                batch.undone(),
                batch.undoable(),
                batch.undoUntil(),
                batch.undoneAt(),
                batch.createdAt(),
                bulk.outcomes(batch.id()));
    }

    /**
     * Runs it, once the number of items is typed back. Over {@code most-items-without-approval} it goes to another admin
     * first, and answers 202 with the request.
     */
    @RequiresPermission(AdminPermission.QUEUES_BULK)
    @PostMapping("/bulk")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "It's running",
            content =
                    @io.swagger.v3.oas.annotations.media.Content(
                            schema =
                                    @io.swagger.v3.oas.annotations.media.Schema(
                                            implementation = AdminBulkAction.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "202",
            description = "It goes to another admin",
            content =
                    @io.swagger.v3.oas.annotations.media.Content(
                            schema =
                                    @io.swagger.v3.oas.annotations.media.Schema(
                                            implementation = Approvals.AdminApproval.class)))
    ResponseEntity<?> run(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody BulkRun request) {
        var count = request.items().stream().distinct().count();
        if (!request.confirm().strip().equals(String.valueOf(count))) {
            throw ApiProblem.invalid("confirm", "must be the number of items, " + count + ".");
        }
        var list = listOr(request.list());
        bulk.preview(admin.id(), list, request.items(), request.action());
        if (count > properties.mostItemsWithoutApproval()) {
            return approvals.submit(
                    admin,
                    BULK,
                    null,
                    null,
                    request,
                    List.of(new Approvals.ApprovalChange(
                            count + " items of " + list, null, request.action().kind())),
                    request.reason());
        }
        var id = bulk.start(admin.id(), list, request.items(), request.action(), request.reason());
        return ResponseEntity.ok(view(bulk.find(admin.id(), id)));
    }

    static final String BULK = "queue.bulk";

    /** The gated bulk action: run as it was asked, with the requester as the actor, once approved. */
    @Bean
    Approvals.GatedAction bulkAction(JsonMapper json) {
        return Approvals.action(BULK, AdminPermission.QUEUES_BULK, Approvals.Cooling.ACCESS, run -> {
            var asked = json.treeToValue(run.params(), BulkRun.class);
            bulk.start(
                    Objects.requireNonNull(run.actor().userId()),
                    listOr(asked.list()),
                    asked.items(),
                    asked.action(),
                    run.reason());
        });
    }

    record AdminBulkActions(List<UUID> actions, @Nullable String nextCursor) {}

    /** This admin's bulk actions, newest first. */
    @RequiresPermission(AdminPermission.QUEUES_READ)
    @AdminList("bulk-actions")
    @GetMapping("/bulk")
    AdminBulkActions bulks(
            @AuthenticationPrincipal CurrentUser admin,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = bulk.page(admin.id(), sort, cursor, limit);
        return new AdminBulkActions(page.rows(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.QUEUES_READ)
    @GetMapping("/bulk/{id}")
    AdminBulkAction bulkAction(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        return view(bulk.find(admin.id(), id));
    }

    /** Puts back what it changed, within its window. 409 after that, or for what can't be undone. */
    @RequiresPermission(AdminPermission.QUEUES_BULK)
    @PostMapping("/bulk/{id}/undo")
    AdminBulkAction undo(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return view(bulk.undo(admin.id(), id, request.reason()));
    }
}
