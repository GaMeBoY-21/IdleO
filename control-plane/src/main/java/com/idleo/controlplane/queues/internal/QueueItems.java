package com.idleo.controlplane.queues.internal;

import com.idleo.controlplane.AdminListScope;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.WorkQueue;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The inbox's items and what an admin does to them directly: claiming, releasing, assigning, setting a priority (ADR 0053). */
@Component
class QueueItems implements AdminListScope {

    /** The inbox, its views, orders and CSV (ADR 0052). Priority first, then the soonest due: one number, {@code sort_key}. */
    static final AdminListing LIST = AdminListing.of("inbox", AdminPermission.QUEUES_READ, true)
            .bulkOfItsOwn("assign", "tag", "priority", "close", "macro", "warn")
            .from("queues.items")
            .sort("priority", AdminListing.Type.NUMBER, "sort_key")
            .sort("due", AdminListing.Type.TIME, "coalesce(due_at, timestamptz 'infinity')")
            .sort("openedAt", AdminListing.Type.TIME, "opened_at")
            .sort("type", AdminListing.Type.TEXT, "type")
            .sortedBy("priority", true)
            .filter("types", "type in (:types)")
            .filter("active", "state <> 'resolved' and cast(:active as text) is not null")
            .filter("state", "state = cast(:state as text)")
            .filter("type", "type = cast(:type as text)")
            .filter("assignee", "assignee_id = cast(:assignee as uuid)")
            .filter("unassigned", "assignee_id is null and cast(:unassigned as text) is not null")
            .filter("breachingAt", "soon_at <= cast(:breachingAt as timestamptz)")
            .filter("tagged", "person_id in (:tagged)")
            .csv("id", "id")
            .csv("type", "type")
            .csv("title", "title")
            .csv("state", "state")
            .csv("assigneeId", "assignee_id")
            .csv("personId", "person_id")
            .csv("openedAt", "opened_at")
            .csv("dueAt", "due_at")
            .csv("breachedAt", "breached_at")
            .csv("moneyPaise", "money_paise")
            .build();

    /** One item, as the module and this one know it. */
    record Row(
            UUID id,
            String type,
            String sourceId,
            @Nullable String subjectKind,
            @Nullable UUID subjectId,
            @Nullable UUID person,
            String title,
            String link,
            String sla,
            Instant openedAt,
            String state,
            @Nullable UUID assignee,
            @Nullable Instant dueAt,
            boolean paused,
            @Nullable Instant breachedAt,
            long moneyPaise,
            boolean runningSession,
            int score,
            @Nullable Integer override,
            @Nullable String overrideReason,
            long version) {

        int rank() {
            return override != null ? override : score;
        }
    }

    private final JdbcClient jdbc;
    private final AdminAccess access;
    private final AuditTrail audit;
    private final Clock clock;
    private final Map<String, AdminPermission> permissions;

    QueueItems(JdbcClient jdbc, AdminAccess access, AuditTrail audit, Clock clock, List<WorkQueue> queues) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.permissions = queues.stream()
                .collect(Collectors.toMap(
                        WorkQueue::type, WorkQueue::permission, (one, other) -> one, LinkedHashMap::new));
    }

    static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("source_id"),
                rs.getString("subject_kind"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("person_id", UUID.class),
                rs.getString("title"),
                rs.getString("link"),
                rs.getString("sla"),
                rs.getTimestamp("opened_at").toInstant(),
                rs.getString("state"),
                rs.getObject("assignee_id", UUID.class),
                rs.getTimestamp("due_at") == null
                        ? null
                        : rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("paused_since") != null,
                rs.getTimestamp("breached_at") == null
                        ? null
                        : rs.getTimestamp("breached_at").toInstant(),
                rs.getLong("money_paise"),
                rs.getBoolean("running_session"),
                rs.getInt("score"),
                rs.getObject("override_score", Integer.class),
                rs.getString("override_reason"),
                rs.getLong("version"));
    }

    /** The kinds of work this admin may see: what they can't read isn't in their inbox at all. */
    Set<String> typesFor(UUID admin) {
        var held = access.permissions(admin);
        return permissions.entrySet().stream()
                .filter(entry -> held.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Never empty, so the filter's {@code in} stays valid: a kind nothing has stands in for none. */
    private List<String> typesIn(UUID admin) {
        var types = typesFor(admin);
        return types.isEmpty() ? List.of("none") : List.copyOf(types);
    }

    @Override
    public String list() {
        return LIST.name();
    }

    @Override
    public Map<String, Object> forAdmin(UUID admin) {
        return Map.of("types", typesIn(admin));
    }

    /**
     * A page of the inbox for this admin.
     *
     * @param view mine, unassigned, breaching, or all
     */
    AdminListing.Page<Row> page(
            UUID admin,
            String view,
            @Nullable String type,
            @Nullable String state,
            @Nullable List<UUID> tagged,
            @Nullable String sort,
            @Nullable String cursor,
            @Nullable Integer limit) {
        var filters = new LinkedHashMap<String, @Nullable Object>();
        filters.put("types", typesIn(admin));
        filters.put("type", type);
        filters.put("tagged", tagged);
        if (state != null && !state.isBlank()) {
            filters.put("state", state);
        } else {
            filters.put("active", "yes");
        }
        switch (view) {
            case "mine" -> filters.put("assignee", admin);
            case "unassigned" -> filters.put("unassigned", "yes");
            case "breaching" -> filters.put("breachingAt", Timestamp.from(clock.instant()));
            case "all" -> {
                // Everything this admin may see.
            }
            default -> throw ApiProblem.invalid("view", "must be mine, unassigned, breaching or all.");
        }
        return LIST.page(jdbc, new AdminListing.Asked(filters, sort), cursor, limit, QueueItems::row);
    }

    /** What the sidebar shows. */
    record Counts(long mine, long unassigned, long breaching) {}

    Counts counts(UUID admin) {
        return jdbc.sql("""
                        select count(*) filter (where assignee_id = :admin) as mine,
                               count(*) filter (where assignee_id is null) as unassigned,
                               count(*) filter (where soon_at <= :now) as breaching
                        from queues.items where state <> 'resolved' and type in (:types)
                        """)
                .param("admin", admin)
                .param("now", Timestamp.from(clock.instant()))
                .param("types", typesIn(admin))
                .query((rs, n) -> new Counts(rs.getLong("mine"), rs.getLong("unassigned"), rs.getLong("breaching")))
                .single();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql("select * from queues.items where id = :id")
                .param("id", id)
                .query(QueueItems::row)
                .optional();
    }

    /** The item, if this admin may see its kind: otherwise as if it weren't there. */
    Row visible(UUID admin, UUID id) {
        return find(id).filter(row -> typesFor(admin).contains(row.type()))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such item."));
    }

    /** Who's on it, by the name other admins see. */
    Map<UUID, String> names() {
        return access.mentionable().stream()
                .collect(Collectors.toMap(
                        AdminAccess.Mentionable::id, AdminAccess.Mentionable::name, (one, other) -> one));
    }

    private static AuditTrail.Actor actor(UUID admin) {
        return new AuditTrail.Actor(AuditTrail.Actor.Kind.ADMIN, admin, admin.toString());
    }

    /**
     * Puts it on this admin: of two at once, exactly one wins, and the other is told who has it. Taking what's already
     * yours changes nothing.
     */
    @Transactional
    Row claim(UUID admin, UUID id) {
        var row = visible(admin, id);
        var now = Timestamp.from(clock.instant());
        var won = jdbc.sql("""
                        update queues.items
                        set assignee_id = :admin, claimed_at = :now, last_action_at = :now, version = version + 1,
                            state = case when state = 'waiting' then 'waiting' else 'claimed' end
                        where id = :id and assignee_id is null and state <> 'resolved'
                        """)
                .param("admin", admin)
                .param("now", now)
                .param("id", id)
                .update();
        var after = find(id).orElseThrow();
        if (won == 0) {
            if (admin.equals(after.assignee())) {
                return after;
            }
            if (after.state().equals("resolved")) {
                throw ApiProblem.of(Problem.ACTION_REFUSED, "This was resolved already.");
            }
            throw ApiProblem.of(Problem.ACTION_REFUSED, nameOf(after.assignee()) + " is on it.");
        }
        audit.record(new AuditTrail.Entry(
                actor(admin),
                "queue.claim",
                "queue_item",
                id.toString(),
                "claimed " + row.title(),
                null,
                Map.of("assignee", admin)));
        return after;
    }

    private String nameOf(@Nullable UUID admin) {
        return admin == null ? "Someone" : names().getOrDefault(admin, "Another admin");
    }

    /** Lets go of it. Only whoever's on it, or someone who may assign, can. */
    @Transactional
    Row release(UUID admin, UUID id) {
        var row = visible(admin, id);
        if (row.assignee() == null) {
            return row;
        }
        if (!admin.equals(row.assignee()) && !access.permissions(admin).contains(AdminPermission.QUEUES_ASSIGN)) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, nameOf(row.assignee()) + " is on it.");
        }
        write(id, null);
        audit.record(new AuditTrail.Entry(
                actor(admin),
                "queue.release",
                "queue_item",
                id.toString(),
                "let go of " + row.title(),
                Map.of("assignee", row.assignee()),
                Map.of("assignee", "none")));
        return find(id).orElseThrow();
    }

    /** Puts it on another admin, who must be able to see its kind. */
    @Transactional
    Row assign(UUID admin, UUID id, UUID assignee, String reason) {
        var row = visible(admin, id);
        if (row.state().equals("resolved")) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This was resolved already.");
        }
        if (!typesFor(assignee).contains(row.type())) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, nameOf(assignee) + " can't see this kind of work.");
        }
        write(id, assignee);
        audit.record(new AuditTrail.Entry(
                actor(admin),
                "queue.assign",
                "queue_item",
                id.toString(),
                reason,
                row.assignee() == null ? Map.of("assignee", "none") : Map.of("assignee", row.assignee()),
                Map.of("assignee", assignee)));
        return find(id).orElseThrow();
    }

    /** Sets who's on it, with no audit of its own: a bulk action audits as a batch, and undoes with this. */
    void write(UUID id, @Nullable UUID assignee) {
        var now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        update queues.items
                        set assignee_id = cast(:assignee as uuid),
                            claimed_at = case when cast(:assignee as uuid) is null then null else cast(:now as timestamptz) end,
                            last_action_at = :now, version = version + 1,
                            state = case
                                when state in ('waiting', 'resolved') then state
                                when cast(:assignee as uuid) is null then 'open'
                                else 'claimed' end
                        where id = :id
                        """)
                .param("assignee", assignee)
                .param("now", now)
                .param("id", id)
                .update();
    }

    /** Overrides its priority, or gives it back to the sum when {@code priority} is null. A reason is always kept. */
    @Transactional
    Row prioritise(UUID admin, UUID id, @Nullable String priority, String reason) {
        var row = visible(admin, id);
        writePriority(row, priority, reason);
        audit.record(new AuditTrail.Entry(
                actor(admin),
                "queue.priority",
                "queue_item",
                id.toString(),
                reason,
                Map.of("priority", QueueTimers.label(row.rank())),
                Map.of("priority", priority == null ? "computed" : priority)));
        return find(id).orElseThrow();
    }

    void writePriority(Row row, @Nullable String priority, @Nullable String reason) {
        Integer override = null;
        if (priority != null) {
            override = QueueTimers.OVERRIDE.get(priority);
            if (override == null) {
                throw ApiProblem.invalid("priority", "must be urgent, high, normal or low.");
            }
        }
        var rank = override != null ? override : row.score();
        jdbc.sql("""
                        update queues.items
                        set override_score = :override, override_reason = :reason, sort_key = :sortKey,
                            last_action_at = :now, version = version + 1
                        where id = :id
                        """)
                .param("override", override)
                .param("reason", priority == null ? null : reason)
                .param("sortKey", QueueTimers.sortKey(rank, row.dueAt()))
                .param("now", Timestamp.from(clock.instant()))
                .param("id", row.id())
                .update();
    }
}
