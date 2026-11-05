package com.idleo.controlplane.queues.internal;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.WorkQueue;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bulk actions on the inbox (ADR 0053): a preview of exactly what each item will become, one batch that runs item by item
 * and reports each, and undo for what can be undone. The preview and the run share {@link #plan}, so a preview can't
 * promise what the run won't do. Money and any enforcement beyond a warning are never bulk: they aren't actions here.
 */
@Component
class BulkActions {

    private static final Logger log = LoggerFactory.getLogger(BulkActions.class);

    /** What can be asked. Assign, tag, priority and close can be undone; a reply can't be unsent. */
    static final Set<String> KINDS = Set.of("assign", "tag", "priority", "close", "macro");

    private static final Set<String> UNDOABLE = Set.of("assign", "tag", "priority", "close");

    /** The most one batch may hold: a file's worth of selections, far past anything a person reviews. */
    static final int MOST_ITEMS = 500;

    /** What's asked. Which of the others apply depends on {@code kind}. */
    record BulkAsked(
            String kind,
            @Nullable UUID assignee,
            @Nullable String tag,
            @Nullable String priority,
            @Nullable UUID macro) {}

    /** What an item is, in the terms an action changes. */
    record BulkShape(
            @Nullable UUID assignee,
            String priority,
            @Nullable String override,
            String state,
            boolean tagged) {}

    /** One item's preview. {@code applies} is false when nothing would change, and {@code why} says so. */
    record BulkChange(
            UUID item,
            String title,
            BulkShape before,
            BulkShape after,
            boolean applies,
            @Nullable String why) {}

    /** A preview: each item and the counts. */
    record BulkPreview(List<BulkChange> changes, int willChange, int unchanged) {}

    /** A batch as it stands. */
    record Batch(
            UUID id,
            BulkAsked action,
            String state,
            int total,
            int done,
            int skipped,
            int failed,
            int undone,
            boolean undoable,
            @Nullable Instant undoUntil,
            @Nullable Instant undoneAt,
            Instant createdAt) {}

    /** One item's outcome. */
    record BulkOutcome(
            UUID item,
            String state,
            @Nullable BulkShape before,
            @Nullable BulkShape after,
            @Nullable String failure) {}

    /** The batches, their orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("bulk-actions", AdminPermission.QUEUES_READ, false)
            .from("queues.bulk_batches")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sortedBy("createdAt", true)
            .filter("requestedBy", "requested_by = cast(:requestedBy as uuid)")
            .csv("id", "id")
            .csv("requestedBy", "requested_by")
            .csv("action", "action->>'kind'")
            .csv("state", "state")
            .csv("total", "total")
            .csv("createdAt", "created_at")
            .build();

    private final JdbcClient jdbc;
    private final QueueItems items;
    private final Macros macros;
    private final QueueSync sync;
    private final AdminTagged tagged;
    private final List<WorkQueue> queues;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final QueueProperties properties;
    private final JsonMapper json;
    private final TransactionTemplate transactions;

    BulkActions(
            JdbcClient jdbc,
            QueueItems items,
            Macros macros,
            QueueSync sync,
            AdminTagged tagged,
            List<WorkQueue> queues,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            QueueProperties properties,
            JsonMapper json,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.items = items;
        this.macros = macros;
        this.sync = sync;
        this.tagged = tagged;
        this.queues = queues;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
        this.properties = properties;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    private void check(BulkAsked action) {
        if (!KINDS.contains(action.kind())) {
            throw ApiProblem.invalid("kind", "must be assign, tag, priority, close or macro.");
        }
        switch (action.kind()) {
            case "assign" -> {
                // No one is letting go: an assignee is optional.
            }
            case "tag" -> {
                if (action.tag() == null || action.tag().isBlank()) {
                    throw ApiProblem.invalid("tag", "says which tag.");
                }
            }
            case "priority" -> {
                if (action.priority() != null && !QueueTimers.OVERRIDE.containsKey(action.priority())) {
                    throw ApiProblem.invalid("priority", "must be urgent, high, normal or low.");
                }
            }
            case "macro" -> {
                if (action.macro() == null) {
                    throw ApiProblem.invalid("macro", "says which macro.");
                }
            }
            default -> {
                // Closing needs nothing more.
            }
        }
    }

    private WorkQueue statusOf(String type) {
        return queues.stream()
                .filter(queue -> queue.type().equals(type) && queue.hasStatus())
                .findFirst()
                .orElse(null);
    }

    private BulkShape shape(QueueItems.Row row, BulkAsked action) {
        return new BulkShape(
                row.assignee(),
                QueueTimers.label(row.rank()),
                row.override() == null ? null : QueueTimers.label(row.override()),
                row.state(),
                carries(row, action.tag()));
    }

    private boolean carries(QueueItems.Row row, @Nullable String tag) {
        return tag != null && row.person() != null && tagged.carries("user", row.person(), tag);
    }

    /** What this action makes of this item, from what it is now. */
    BulkChange plan(QueueItems.Row row, BulkAsked action) {
        var before = shape(row, action);
        var after = before;
        String why = null;
        if (row.state().equals("resolved")) {
            why = "It's resolved already.";
        } else {
            switch (action.kind()) {
                case "assign" -> {
                    if (Objects.equals(row.assignee(), action.assignee())) {
                        why = "They're on it already.";
                    } else if (action.assignee() != null
                            && !items.typesFor(action.assignee()).contains(row.type())) {
                        why = "They can't see this kind of work.";
                    } else {
                        after = new BulkShape(
                                action.assignee(),
                                before.priority(),
                                before.override(),
                                action.assignee() == null
                                        ? (row.state().equals("waiting") ? "waiting" : "open")
                                        : (row.state().equals("waiting") ? "waiting" : "claimed"),
                                before.tagged());
                    }
                }
                case "tag" -> {
                    if (row.person() == null) {
                        why = "It's nobody's, so there's no one to tag.";
                    } else if (before.tagged()) {
                        why = "They have this tag already.";
                    } else {
                        after = new BulkShape(
                                before.assignee(), before.priority(), before.override(), before.state(), true);
                    }
                }
                case "priority" -> {
                    var label = action.priority() == null ? QueueTimers.label(row.score()) : action.priority();
                    if (Objects.equals(before.override(), action.priority())) {
                        why = "It's at that priority already.";
                    } else {
                        after = new BulkShape(
                                before.assignee(), label, action.priority(), before.state(), before.tagged());
                    }
                }
                case "close" -> {
                    if (statusOf(row.type()) == null) {
                        why = "Only a ticket can be closed from here.";
                    } else {
                        after = new BulkShape(
                                before.assignee(), before.priority(), before.override(), "resolved", before.tagged());
                    }
                }
                case "macro" -> {
                    var macro = macros.find(Objects.requireNonNull(action.macro()));
                    if (!macro.type().equals(row.type())) {
                        why = "This macro is for another kind of work.";
                    } else {
                        after = new BulkShape(
                                before.assignee(),
                                macro.setPriority() == null ? before.priority() : macro.setPriority(),
                                macro.setPriority() == null ? before.override() : macro.setPriority(),
                                macro.setStatus() == null
                                        ? (macro.body() == null ? before.state() : "waiting")
                                        : macro.setStatus(),
                                before.tagged());
                    }
                }
                default -> throw new IllegalStateException(action.kind());
            }
        }
        return new BulkChange(row.id(), row.title(), before, after, why == null, why);
    }

    private List<QueueItems.Row> rows(UUID admin, List<UUID> ids) {
        if (ids.isEmpty() || ids.size() > MOST_ITEMS) {
            throw ApiProblem.invalid("items", "must be from 1 to " + MOST_ITEMS + " items.");
        }
        return ids.stream().distinct().map(id -> items.visible(admin, id)).toList();
    }

    /** Exactly what each item will become, changing nothing. */
    BulkPreview preview(UUID admin, List<UUID> ids, BulkAsked action) {
        check(action);
        var changes = rows(admin, ids).stream().map(row -> plan(row, action)).toList();
        var willChange = (int) changes.stream().filter(BulkChange::applies).count();
        return new BulkPreview(changes, willChange, changes.size() - willChange);
    }

    /** Queues a batch. Its items are checked now, each is planned again as it runs. */
    UUID start(UUID admin, List<UUID> ids, BulkAsked action, String reason) {
        check(action);
        var rows = rows(admin, ids);
        var id = this.ids.newId();
        var now = Timestamp.from(clock.instant());
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            insert into queues.bulk_batches (id, requested_by, action, reason, state, total, undoable, created_at)
                            values (:id, :admin, cast(:action as jsonb), :reason, 'queued', :total, :undoable, :now)
                            """)
                    .param("id", id)
                    .param("admin", admin)
                    .param("action", json.writeValueAsString(action))
                    .param("reason", reason)
                    .param("total", rows.size())
                    .param("undoable", UNDOABLE.contains(action.kind()))
                    .param("now", now)
                    .update();
            int position = 0;
            for (var row : rows) {
                jdbc.sql("""
                                insert into queues.bulk_items (batch_id, item_id, position, state)
                                values (:batch, :item, :position, 'pending')
                                """)
                        .param("batch", id)
                        .param("item", row.id())
                        .param("position", position++)
                        .update();
            }
            audit.record(new AuditTrail.Entry(
                    actor(admin),
                    "queue.bulk",
                    "bulk_action",
                    id.toString(),
                    reason,
                    null,
                    Map.of("kind", action.kind(), "items", rows.size())));
        });
        return id;
    }

    private static AuditTrail.Actor actor(UUID admin) {
        return new AuditTrail.Actor(AuditTrail.Actor.Kind.ADMIN, admin, admin.toString());
    }

    @Scheduled(fixedDelayString = "${queues.bulk-every}")
    void scheduled() {
        run();
    }

    /** Runs whatever batch is waiting, or one left behind by an instance that stopped: its finished items stay finished. */
    void run() {
        record Next(UUID id, UUID admin, String action) {}
        var now = clock.instant();
        var next = transactions.execute(status -> jdbc.sql("""
                        select id, requested_by, action::text as action from queues.bulk_batches
                        where state = 'queued' or (state = 'running' and heartbeat_at < :stale)
                        order by created_at limit 1 for update skip locked
                        """)
                .param("stale", Timestamp.from(now.minusSeconds(60)))
                .query((rs, n) -> new Next(
                        rs.getObject("id", UUID.class),
                        rs.getObject("requested_by", UUID.class),
                        rs.getString("action")))
                .optional()
                .map(found -> {
                    jdbc.sql("update queues.bulk_batches set state = 'running', heartbeat_at = :now where id = :id")
                            .param("now", Timestamp.from(now))
                            .param("id", found.id())
                            .update();
                    return found;
                })
                .orElse(null));
        if (next == null) {
            return;
        }
        var action = json.readValue(next.action(), BulkAsked.class);
        var pending = jdbc.sql("""
                        select item_id from queues.bulk_items where batch_id = :batch and state = 'pending' order by position
                        """).param("batch", next.id()).query(UUID.class).list();
        for (var item : pending) {
            one(next.id(), next.admin(), item, action);
            jdbc.sql("update queues.bulk_batches set heartbeat_at = :now where id = :id")
                    .param("now", Timestamp.from(clock.instant()))
                    .param("id", next.id())
                    .update();
        }
        var finished = clock.instant();
        jdbc.sql("""
                        update queues.bulk_batches
                        set state = 'done', finished_at = :now,
                            undo_until = case when undoable then cast(:until as timestamptz) end
                        where id = :id
                        """)
                .param("now", Timestamp.from(finished))
                .param("until", Timestamp.from(finished.plus(properties.undoWindow())))
                .param("id", next.id())
                .update();
    }

    /** One item, in its own transaction: a failure is that item's, and is reported as its own. */
    private void one(UUID batch, UUID admin, UUID id, BulkAsked action) {
        try {
            var row = items.find(id).orElseThrow();
            var change = plan(row, action);
            if (!change.applies()) {
                outcome(batch, id, "skipped", change.before(), change.before(), change.why());
                return;
            }
            apply(admin, row, action);
            var after = items.find(id).orElseThrow();
            outcome(batch, id, "done", change.before(), shape(after, action), null);
        } catch (RuntimeException failed) {
            log.warn("Bulk action {} failed on item {}: {}", batch, id, failed.getMessage());
            outcome(batch, id, "failed", null, null, failed.getMessage());
        }
    }

    private void apply(UUID admin, QueueItems.Row row, BulkAsked action) {
        switch (action.kind()) {
            case "assign" -> transactions.executeWithoutResult(status -> items.write(row.id(), action.assignee()));
            case "tag" ->
                transactions.executeWithoutResult(status -> tagged.add(
                        "user", Objects.requireNonNull(row.person()), Objects.requireNonNull(action.tag()), admin));
            case "priority" ->
                transactions.executeWithoutResult(status -> items.writePriority(row, action.priority(), "bulk action"));
            case "close" -> {
                Objects.requireNonNull(statusOf(row.type())).status(row.sourceId(), "resolved", "bulk action", admin);
                sync.refresh(row.type(), row.sourceId());
            }
            case "macro" -> macros.apply(admin, row, macros.find(Objects.requireNonNull(action.macro())));
            default -> throw new IllegalStateException(action.kind());
        }
    }

    private void outcome(
            UUID batch,
            UUID item,
            String state,
            @Nullable BulkShape before,
            @Nullable BulkShape after,
            @Nullable String why) {
        jdbc.sql("""
                        update queues.bulk_items
                        set state = :state, before = cast(:before as jsonb), after = cast(:after as jsonb), failure = :why
                        where batch_id = :batch and item_id = :item and state = 'pending'
                        """)
                .param("state", state)
                .param("before", before == null ? null : json.writeValueAsString(before))
                .param("after", after == null ? null : json.writeValueAsString(after))
                .param("why", why)
                .param("batch", batch)
                .param("item", item)
                .update();
    }

    private Batch batch(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Batch(
                rs.getObject("id", UUID.class),
                json.readValue(rs.getString("action"), BulkAsked.class),
                rs.getString("state"),
                rs.getInt("total"),
                rs.getInt("done"),
                rs.getInt("skipped"),
                rs.getInt("failed"),
                rs.getInt("undone"),
                rs.getBoolean("undoable"),
                rs.getTimestamp("undo_until") == null
                        ? null
                        : rs.getTimestamp("undo_until").toInstant(),
                rs.getTimestamp("undone_at") == null
                        ? null
                        : rs.getTimestamp("undone_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static final String TALLIES = """
            (select count(*) filter (where i.state = 'done') from queues.bulk_items i where i.batch_id = b.id) as done,
            (select count(*) filter (where i.state = 'skipped') from queues.bulk_items i where i.batch_id = b.id) as skipped,
            (select count(*) filter (where i.state = 'failed') from queues.bulk_items i where i.batch_id = b.id) as failed,
            (select count(*) filter (where i.state = 'undone') from queues.bulk_items i where i.batch_id = b.id) as undone
            """;

    /** A batch of this admin's, or as if it weren't there. */
    Batch find(UUID admin, UUID id) {
        return jdbc.sql("select b.*, " + TALLIES
                        + " from queues.bulk_batches b where b.id = :id and b.requested_by = :admin")
                .param("id", id)
                .param("admin", admin)
                .query(this::batch)
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such bulk action."));
    }

    List<BulkOutcome> outcomes(UUID batch) {
        return jdbc.sql("select * from queues.bulk_items where batch_id = :batch order by position")
                .param("batch", batch)
                .query((rs, n) -> new BulkOutcome(
                        rs.getObject("item_id", UUID.class),
                        rs.getString("state"),
                        rs.getString("before") == null ? null : json.readValue(rs.getString("before"), BulkShape.class),
                        rs.getString("after") == null ? null : json.readValue(rs.getString("after"), BulkShape.class),
                        rs.getString("failure")))
                .list();
    }

    AdminListing.Page<UUID> page(UUID admin, @Nullable String sort, @Nullable String cursor, @Nullable Integer limit) {
        return LIST.page(
                jdbc,
                AdminListing.Asked.of(sort, "requestedBy", admin),
                cursor,
                limit,
                (rs, n) -> rs.getObject("id", UUID.class));
    }

    /** Puts back what this batch changed, within its window: an item someone's changed since is left and reported. */
    Batch undo(UUID admin, UUID id, String reason) {
        var batch = find(admin, id);
        if (!batch.undoable()) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "A " + batch.action().kind() + " can't be undone.");
        }
        if (batch.undoneAt() != null) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This was undone already.");
        }
        if (!"done".equals(batch.state())
                || batch.undoUntil() == null
                || clock.instant().isAfter(batch.undoUntil())) {
            throw ApiProblem.of(
                    Problem.ACTION_REFUSED,
                    "It can be undone for only " + properties.undoWindow().toMinutes()
                            + " minutes, and that's passed.");
        }
        for (var outcome : outcomes(id)) {
            if (!"done".equals(outcome.state())) {
                continue;
            }
            try {
                var row = items.find(outcome.item()).orElseThrow();
                revert(admin, row, batch.action(), Objects.requireNonNull(outcome.before()));
                jdbc.sql("update queues.bulk_items set state = 'undone' where batch_id = :batch and item_id = :item")
                        .param("batch", id)
                        .param("item", outcome.item())
                        .update();
            } catch (RuntimeException failed) {
                jdbc.sql("update queues.bulk_items set failure = :why where batch_id = :batch and item_id = :item")
                        .param("why", "couldn't be undone: " + failed.getMessage())
                        .param("batch", id)
                        .param("item", outcome.item())
                        .update();
            }
        }
        transactions.executeWithoutResult(status -> {
            jdbc.sql("update queues.bulk_batches set undone_at = :now where id = :id")
                    .param("now", Timestamp.from(clock.instant()))
                    .param("id", id)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor(admin),
                    "queue.bulk_undo",
                    "bulk_action",
                    id.toString(),
                    reason,
                    null,
                    Map.of("kind", batch.action().kind())));
        });
        return find(admin, id);
    }

    private void revert(UUID admin, QueueItems.Row row, BulkAsked action, BulkShape before) {
        switch (action.kind()) {
            case "assign" -> transactions.executeWithoutResult(status -> items.write(row.id(), before.assignee()));
            case "tag" ->
                transactions.executeWithoutResult(status -> tagged.remove(
                        "user", Objects.requireNonNull(row.person()), Objects.requireNonNull(action.tag()), admin));
            case "priority" ->
                transactions.executeWithoutResult(
                        status -> items.writePriority(row, before.override(), "bulk action undone"));
            case "close" -> {
                Objects.requireNonNull(statusOf(row.type()))
                        .status(
                                row.sourceId(),
                                before.state().equals("waiting") ? "waiting" : "open",
                                "bulk action undone",
                                admin);
                sync.refresh(row.type(), row.sourceId());
            }
            default -> throw new IllegalStateException(action.kind());
        }
    }
}
