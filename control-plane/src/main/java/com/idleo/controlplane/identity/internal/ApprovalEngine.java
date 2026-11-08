package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AdminNoticed;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one approval engine (ADR 0051). A gated action becomes a request; another admin with its permission approves or
 * rejects it, and on approval it runs as it was asked, refused if its target's version has changed. The requester can
 * never approve their own request. Requests expire after {@code expires-after}.
 *
 * <p><b>Solo mode:</b> when no one else holds the permission, the request waits a cooling-off delay instead, the Owners are
 * told, and it runs as "self-approved after delay" unless cancelled. If a second eligible admin exists by the time it's
 * due, or at any sweep, solo mode switches off by itself and it waits for them.
 *
 * <p>A request is decided under its row's lock and marked {@code running} before the action starts, in a commit of its
 * own, so it can run only once. The action then runs in its own transactions, because it may do side effects after them. A
 * crash in between leaves it {@code running}, and the sweep marks it failed for a person to look at: never run twice.
 */
@Service
@ConditionalOnWebApplication
@EnableConfigurationProperties(ApprovalProperties.class)
class ApprovalEngine implements Approvals {

    private static final Logger log = LoggerFactory.getLogger(ApprovalEngine.class);
    private static final TypeReference<List<ApprovalChange>> CHANGES = new TypeReference<>() {};
    private static final java.time.Duration RUNNING_TOO_LONG = java.time.Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final JsonMapper json;
    private final AuditTrail audit;
    private final AdminPermissions permissions;
    private final AdminSafeWrites safeWrites;
    private final AdminInsiderSignals insider;
    private final ApplicationEventPublisher events;
    private final ApprovalProperties properties;
    private final ObjectProvider<GatedAction> gated;
    private volatile @Nullable Map<String, GatedAction> actions;

    ApprovalEngine(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            IdGenerator ids,
            Clock clock,
            JsonMapper json,
            AuditTrail audit,
            AdminPermissions permissions,
            AdminSafeWrites safeWrites,
            AdminInsiderSignals insider,
            ApplicationEventPublisher events,
            ApprovalProperties properties,
            ObjectProvider<GatedAction> gated) {
        this.gated = gated;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
        this.permissions = permissions;
        this.safeWrites = safeWrites;
        this.insider = insider;
        this.events = events;
        this.properties = properties;
    }

    /** Asked for only when first needed: an action's controller also asks this engine for requests. */
    private Map<String, GatedAction> actions() {
        var known = actions;
        if (known != null) {
            return known;
        }
        var all = new HashMap<String, GatedAction>();
        gated.forEach(action -> {
            if (all.put(action.kind(), action) != null) {
                throw new IllegalStateException(action.kind() + " is gated by two modules");
            }
        });
        actions = Map.copyOf(all);
        return all;
    }

    @Override
    public boolean refundNeedsApproval(Paise amount) {
        return amount.value() > properties.refundLimitPaise();
    }

    @Override
    public ResponseEntity<AdminApproval> submit(
            CurrentUser admin,
            String kind,
            @Nullable String targetType,
            @Nullable String targetId,
            Object params,
            List<ApprovalChange> changes,
            String reason) {
        var action = action(kind);
        var version = targetType == null || targetId == null
                ? null
                : safeWrites.version(targetType, Map.of("id", targetId)).stream()
                        .boxed()
                        .findFirst()
                        .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such record."));
        var id = ids.newId();
        var now = now();
        boolean solo =
                permissions.holders(action.permission()).stream().noneMatch(holder -> !holder.equals(admin.id()));
        var runAfter = solo ? now.plus(cooling(action)) : null;
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            insert into identity.approval_requests
                                (id, kind, permission, target_type, target_id, target_version, params, changes,
                                 requested_by, requested_session, reason, state, solo, run_after, expires_at, created_at,
                                 download_kind)
                            values (:id, :kind, :permission, :targetType, :targetId, :version, cast(:params as jsonb),
                                    cast(:changes as jsonb), :by, :session, :reason, 'pending', :solo, :runAfter, :expires,
                                    :now, :download)
                            """)
                    .param("id", id)
                    .param("kind", kind)
                    .param("permission", action.permission().key())
                    .param("targetType", targetType)
                    .param("targetId", targetId)
                    .param("version", version)
                    .param("params", json.writeValueAsString(params))
                    .param("changes", json.writeValueAsString(changes))
                    .param("by", admin.id())
                    .param("session", AdminRequestContext.adminSession())
                    .param("reason", reason)
                    .param("solo", solo)
                    .param("runAfter", runAfter == null ? null : Timestamp.from(runAfter))
                    .param("expires", Timestamp.from(now.plus(properties.expiresAfter())))
                    .param("now", Timestamp.from(now))
                    .param("download", action.downloads() ? kind : null)
                    .update();
            audit.record(new AuditTrail.Entry(
                    audit.admin(admin),
                    "approval.request",
                    "approval",
                    id.toString(),
                    reason,
                    null,
                    Map.of("kind", kind, "solo", solo)));
            if (solo) {
                insider.soloApproval();
                tellTheOwners(id, kind, admin.id(), Objects.requireNonNull(runAfter), action.cooling() != Cooling.NONE);
            }
        });
        if (solo && action.cooling() == Cooling.NONE) {
            // Running a kill switch again (ADR 0056): with no one else to approve it, waiting would only make the
            // outage longer, so it runs now, self-approved, and the Owners have been told.
            advanceSolo(id);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(find(id).orElseThrow());
    }

    private void tellTheOwners(UUID id, String kind, UUID requester, Instant runAfter, boolean waits) {
        for (var owner : permissions.holdersOfRole("owner")) {
            events.publishEvent(new AdminNoticed(
                    owner,
                    "approval-solo:" + id + ":" + owner,
                    waits ? "An admin action will run after a delay" : "An admin action ran with no one to approve it",
                    waits
                            ? "A " + kind + " asked for by admin " + requester
                                    + " has no one else to approve it, so it runs at " + runAfter
                                    + " unless it's cancelled. Sign in to the console to read it or cancel it."
                            : "A " + kind + " asked for by admin " + requester
                                    + " had no one else to approve it and doesn't wait, so it ran at once. Sign in to"
                                    + " the console to read it.",
                    "/admin/approvals/"));
        }
    }

    private GatedAction action(String kind) {
        var action = actions().get(kind);
        if (action == null) {
            throw new IllegalStateException("no module does the gated action " + kind);
        }
        return action;
    }

    private java.time.Duration cooling(GatedAction action) {
        return switch (action.cooling()) {
            case MONEY -> properties.coolingMoney();
            case ACCESS -> properties.coolingAccess();
            case NONE -> java.time.Duration.ZERO;
        };
    }

    /** Whether this approver may decide this kind: they hold its permission. */
    private void checkMayDecide(CurrentUser approver, Row row) {
        var needed = AdminPermission.of(row.permission());
        if (!permissions.has(approver.id(), needed)) {
            throw ApiProblem.of(
                    Problem.ADMIN_PERMISSION_REQUIRED,
                    "You need the " + needed.key() + " permission to decide this.",
                    "permission",
                    needed.key());
        }
    }

    /** Approves and runs it, as it was asked. */
    AdminApproval approve(CurrentUser approver, UUID id, String reason) {
        var row = Objects.requireNonNull(transactions.execute(status -> {
            var locked = lockPending(id);
            checkMayDecide(approver, locked);
            if (locked.requestedBy().equals(approver.id())) {
                throw ApiProblem.of(Problem.ACTION_REFUSED, "You asked for this, so someone else must approve it.");
            }
            mark(id, "running", approver.id(), reason, null);
            audit.record(new AuditTrail.Entry(
                    audit.admin(approver),
                    "approval.approve",
                    "approval",
                    id.toString(),
                    reason,
                    Map.of("state", "pending"),
                    Map.of("state", "running", "kind", locked.kind())));
            return locked;
        }));
        var failure = run(row, id);
        if (failure != null) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, failure);
        }
        return find(id).orElseThrow();
    }

    /** Rejects it: nothing runs. */
    AdminApproval reject(CurrentUser approver, UUID id, String reason) {
        transactions.executeWithoutResult(status -> {
            var locked = lockPending(id);
            checkMayDecide(approver, locked);
            mark(id, "rejected", approver.id(), reason, null);
            audit.record(new AuditTrail.Entry(
                    audit.admin(approver),
                    "approval.reject",
                    "approval",
                    id.toString(),
                    reason,
                    Map.of("state", "pending"),
                    Map.of("state", "rejected", "kind", locked.kind())));
        });
        return find(id).orElseThrow();
    }

    /** Cancels a request still waiting: by whoever asked for it, or anyone who may decide it, or an Owner. */
    AdminApproval cancel(CurrentUser admin, UUID id, String reason) {
        transactions.executeWithoutResult(status -> {
            var locked = lockPending(id);
            if (!locked.requestedBy().equals(admin.id())
                    && !permissions.has(admin.id(), AdminPermission.ADMINS_MANAGE)) {
                checkMayDecide(admin, locked);
            }
            mark(id, "cancelled", admin.id(), reason, null);
            audit.record(new AuditTrail.Entry(
                    audit.admin(admin),
                    "approval.cancel",
                    "approval",
                    id.toString(),
                    reason,
                    Map.of("state", "pending"),
                    Map.of("state", "cancelled", "kind", locked.kind())));
        });
        return find(id).orElseThrow();
    }

    /**
     * The file an approved export makes, once, for whoever asked for it. Audited as the action itself is: this is when
     * the data leaves.
     */
    File download(CurrentUser admin, UUID id) {
        var row = transactions.execute(status -> {
            var locked = jdbc.sql("select * from identity.approval_requests where id = :id for update")
                    .param("id", id)
                    .query(this::row)
                    .optional()
                    .orElseThrow(ApprovalEngine::noSuchRequest);
            if (!locked.requestedBy().equals(admin.id())) {
                throw noSuchRequest();
            }
            if (!locked.downloadable(Instant.now(clock), properties.downloadFor())) {
                throw ApiProblem.of(
                        Problem.ACTION_REFUSED,
                        "This has been fetched already, or isn't approved, or its day has passed.");
            }
            jdbc.sql("update identity.approval_requests set downloaded_at = :now where id = :id")
                    .param("now", Timestamp.from(now()))
                    .param("id", id)
                    .update();
            return locked;
        });
        var known = Objects.requireNonNull(row);
        return ApprovalContext.running(
                id,
                () -> action(known.kind())
                        .download(new Run(id, json.readTree(known.params()), known.reason(), requester(known)))
                        .orElseThrow(() -> ApiProblem.of(Problem.ACTION_REFUSED, "This has no file to fetch.")));
    }

    private Row lockPending(UUID id) {
        var row = jdbc.sql("select * from identity.approval_requests where id = :id for update")
                .param("id", id)
                .query(this::row)
                .optional()
                .orElseThrow(ApprovalEngine::noSuchRequest);
        if (!"pending".equals(row.state())) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This request is already " + row.state() + ".");
        }
        if (!row.expiresAt().isAfter(now())) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This request has expired.");
        }
        return row;
    }

    private static ApiProblem noSuchRequest() {
        return ApiProblem.of(Problem.NOT_FOUND, "There's no such request.");
    }

    private void mark(UUID id, String state, @Nullable UUID by, @Nullable String reason, @Nullable String failure) {
        var now = Timestamp.from(now());
        jdbc.sql("""
                        update identity.approval_requests
                        set state = :state,
                            decided_by = coalesce(cast(:by as uuid), decided_by),
                            decision_reason = coalesce(cast(:reason as text), decision_reason),
                            decided_at = case when cast(:by as uuid) is null then decided_at else :now end,
                            failure = :failure,
                            finished_at = case when :state in ('done', 'rejected', 'expired', 'cancelled', 'failed')
                                               then :now else finished_at end
                        where id = :id
                        """)
                .param("state", state)
                .param("by", by)
                .param("reason", reason)
                .param("failure", failure)
                .param("now", now)
                .param("id", id)
                .update();
    }

    private AuditTrail.Actor requester(Row row) {
        return audit.admin(new CurrentUser(row.requestedBy()));
    }

    /** Runs a request now marked {@code running}. What went wrong, in words, or null when it's done. */
    private @Nullable String run(Row row, UUID id) {
        var failure = stale(row);
        if (failure == null) {
            try {
                var action = action(row.kind());
                ApprovalContext.running(id, () -> {
                    action.run(new Run(id, json.readTree(row.params()), row.reason(), requester(row)));
                    return null;
                });
            } catch (RuntimeException refused) {
                failure = refused instanceof ApiProblem problem
                        ? problem.detail()
                        : Objects.requireNonNullElse(refused.getMessage(), "It failed.");
                log.warn(
                        "The approved action {} of {} failed: {}",
                        row.kind(),
                        id,
                        refused.getClass().getSimpleName());
            }
        }
        var ended = failure;
        transactions.executeWithoutResult(status -> {
            mark(id, ended == null ? "done" : "failed", null, null, ended);
            if (ended != null) {
                audit.record(new AuditTrail.Entry(
                        AuditTrail.Actor.system("approval engine"),
                        "approval.fail",
                        "approval",
                        id.toString(),
                        "the approved action didn't run",
                        null,
                        Map.of("kind", row.kind())));
            }
        });
        return failure;
    }

    /** Why it may no longer run: its target has changed since it was asked. */
    private @Nullable String stale(Row row) {
        if (row.targetType() == null || row.targetId() == null) {
            return null;
        }
        var now = safeWrites.version(row.targetType(), Map.of("id", row.targetId()));
        if (now.isEmpty() || row.targetVersion() == null || now.getAsLong() != row.targetVersion()) {
            return "The record changed after this was asked for, so it wasn't done. Ask again if it's still right.";
        }
        return null;
    }

    /** Runs what's due, expires what's old, and switches solo mode off where someone else can now decide. */
    @Scheduled(fixedDelayString = "${identity.approvals.sweep-every:1m}", initialDelayString = "PT30S")
    void sweep() {
        var now = Timestamp.from(now());
        // A run that never finished: a crash. Never run again: someone looks at it.
        jdbc.sql("""
                        update identity.approval_requests
                        set state = 'failed', failure = 'It was interrupted before it finished. Check the record.',
                            finished_at = :now
                        where state = 'running' and coalesce(decided_at, run_after) < :before
                        """)
                .param("now", now)
                .param("before", Timestamp.from(now().minus(RUNNING_TOO_LONG)))
                .update();
        for (var id : jdbc.sql("""
                        select id from identity.approval_requests where state = 'pending' and expires_at <= :now
                        """).param("now", now).query(UUID.class).list()) {
            expire(id);
        }
        for (var id : jdbc.sql("""
                        select id from identity.approval_requests where state = 'pending' and solo
                        """).query(UUID.class).list()) {
            advanceSolo(id);
        }
    }

    private void expire(UUID id) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.sql("""
                            update identity.approval_requests set state = 'expired', finished_at = :now
                            where id = :id and state = 'pending'
                            """)
                    .param("now", Timestamp.from(now()))
                    .param("id", id)
                    .update();
            if (changed == 1) {
                audit.record(new AuditTrail.Entry(
                        AuditTrail.Actor.system("approval engine"),
                        "approval.expire",
                        "approval",
                        id.toString(),
                        "no one decided in time",
                        Map.of("state", "pending"),
                        Map.of("state", "expired")));
            }
        });
    }

    /** One solo request: someone else can now decide it, or its delay is over and it runs, once. */
    private void advanceSolo(UUID id) {
        var due = transactions.execute(status -> {
            var row = jdbc.sql("""
                            select * from identity.approval_requests
                            where id = :id and state = 'pending' and solo for update skip locked
                            """).param("id", id).query(this::row).optional().orElse(null);
            if (row == null) {
                return null;
            }
            var needed = AdminPermission.of(row.permission());
            boolean someoneElse =
                    permissions.holders(needed).stream().anyMatch(holder -> !holder.equals(row.requestedBy()));
            if (someoneElse) {
                jdbc.sql("update identity.approval_requests set solo = false, run_after = null where id = :id")
                        .param("id", id)
                        .update();
                return null;
            }
            if (row.runAfter() == null || row.runAfter().isAfter(now())) {
                return null;
            }
            var decision = action(row.kind()).cooling() == Cooling.NONE
                    ? "self-approved: no one else can approve it, and it doesn't wait"
                    : "self-approved after delay";
            mark(id, "running", null, decision, null);
            audit.record(new AuditTrail.Entry(
                    AuditTrail.Actor.system("approval engine"),
                    "approval.self_approve",
                    "approval",
                    id.toString(),
                    decision,
                    Map.of("state", "pending"),
                    Map.of("state", "running", "kind", row.kind())));
            return row;
        });
        if (due != null) {
            run(due, id);
        }
    }

    Optional<AdminApproval> find(UUID id) {
        return jdbc.sql("select * from identity.approval_requests where id = :id")
                .param("id", id)
                .query(this::row)
                .optional()
                .map(this::view);
    }

    /** The requests: what's waiting, what's decided, or what one admin asked for. */
    static final AdminListing LIST = AdminListing.of("approvals", AdminPermission.APPROVALS_READ, true)
            .from("identity.approval_requests")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sort("kind", AdminListing.Type.TEXT, "kind")
            .sort("state", AdminListing.Type.TEXT, "state")
            .sortedBy("createdAt", true)
            .filter("which", """
                    (cast(:which as text) = 'pending' and state = 'pending')
                    or (cast(:which as text) = 'decided' and state <> 'pending')""")
            .filter("requestedBy", "requested_by = cast(:requestedBy as uuid)")
            .csv("id", "id")
            .csv("kind", "kind")
            .csv("targetType", "target_type")
            .csv("targetId", "target_id")
            .csv("requestedBy", "requested_by")
            .csv("reason", "reason")
            .csv("state", "state")
            .csv("decidedBy", "decided_by")
            .csv("decisionReason", "decision_reason")
            .csv("decidedAt", "decided_at")
            .csv("createdAt", "created_at")
            .build();

    /** {@code which}: pending, decided, or mine, which is everything {@code admin} asked for. */
    AdminListing.Page<AdminApproval> list(
            String which, UUID admin, @Nullable String sort, @Nullable String cursor, @Nullable Integer limit) {
        var asked = which.equals("mine")
                ? AdminListing.Asked.of(sort, "requestedBy", admin)
                : AdminListing.Asked.of(sort, "which", which);
        var page = LIST.page(jdbc, asked, cursor, limit, this::row);
        return new AdminListing.Page<>(page.rows().stream().map(this::view).toList(), page.nextCursor());
    }

    long pending() {
        return jdbc.sql("select count(*) from identity.approval_requests where state = 'pending'")
                .query(Long.class)
                .single();
    }

    private record Row(
            UUID id,
            String kind,
            String permission,
            @Nullable String targetType,
            @Nullable String targetId,
            @Nullable Long targetVersion,
            String params,
            String changes,
            UUID requestedBy,
            String reason,
            String state,
            boolean solo,
            @Nullable Instant runAfter,
            @Nullable UUID decidedBy,
            @Nullable String decisionReason,
            @Nullable Instant decidedAt,
            @Nullable String failure,
            @Nullable String downloadKind,
            @Nullable Instant downloadedAt,
            @Nullable Instant finishedAt,
            Instant expiresAt,
            Instant createdAt,
            long version) {

        boolean downloadable(Instant now, java.time.Duration window) {
            return "done".equals(state)
                    && downloadKind != null
                    && downloadedAt == null
                    && finishedAt != null
                    && finishedAt.plus(window).isAfter(now);
        }
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("permission"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getObject("target_version", Long.class),
                rs.getString("params"),
                rs.getString("changes"),
                rs.getObject("requested_by", UUID.class),
                rs.getString("reason"),
                rs.getString("state"),
                rs.getBoolean("solo"),
                instant(rs, "run_after"),
                rs.getObject("decided_by", UUID.class),
                rs.getString("decision_reason"),
                instant(rs, "decided_at"),
                rs.getString("failure"),
                rs.getString("download_kind"),
                instant(rs, "downloaded_at"),
                instant(rs, "finished_at"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }

    private AdminApproval view(Row row) {
        return new AdminApproval(
                row.id(),
                row.kind(),
                row.permission(),
                row.targetType(),
                row.targetId(),
                json.readValue(row.changes(), CHANGES),
                row.requestedBy(),
                row.reason(),
                row.state(),
                row.solo(),
                row.runAfter(),
                row.decidedBy(),
                row.decisionReason(),
                row.decidedAt(),
                row.failure(),
                row.downloadable(Instant.now(clock), properties.downloadFor()),
                row.expiresAt(),
                row.createdAt(),
                row.version());
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
