package com.idleo.controlplane.queues.internal;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.Rupees;
import com.idleo.controlplane.WorkQueue;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.UserDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Macros (ADR 0053): a reply with variables, and changes to its state, tag and priority, in one step. Per kind of work and
 * versioned: an edit is a new version, and applying one is audited with the version it was. Its words go out through the
 * module's own reply: there's no sender here.
 */
@Component
class Macros {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z]+)\\s*}}");
    private static final Set<String> VARIABLES = Set.of("firstName", "amount", "link");

    /** Macros, their orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("macros", AdminPermission.QUEUES_READ, false)
            .select("m.*, v.body, v.set_status, v.add_tag, v.set_priority, v.written_at")
            .from("queues.macros m join queues.macro_versions v on v.macro_id = m.id and v.number = m.current")
            .id("m.id")
            .always("m.retired_at is null")
            .sort("name", AdminListing.Type.TEXT, "m.name")
            .sort("type", AdminListing.Type.TEXT, "m.type")
            .sortedBy("name", false)
            .filter("type", "m.type = cast(:type as text)")
            .csv("id", "m.id")
            .csv("type", "m.type")
            .csv("name", "m.name")
            .csv("versionNumber", "m.current")
            .csv("setStatus", "v.set_status")
            .csv("addTag", "v.add_tag")
            .csv("setPriority", "v.set_priority")
            .build();

    /** A macro as it stands. */
    record Macro(
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

    /** What an edit writes. */
    record Words(
            @Nullable String body,
            @Nullable String setStatus,
            @Nullable String addTag,
            @Nullable String setPriority) {}

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final UserDirectory users;
    private final AdminTagged tagged;
    private final QueueItems items;
    private final QueueSync sync;
    private final List<WorkQueue> queues;
    private final QueueProperties properties;
    private final TransactionTemplate transactions;

    Macros(
            JdbcClient jdbc,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            UserDirectory users,
            AdminTagged tagged,
            QueueItems items,
            QueueSync sync,
            List<WorkQueue> queues,
            QueueProperties properties,
            PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
        this.users = users;
        this.tagged = tagged;
        this.items = items;
        this.sync = sync;
        this.queues = queues;
    }

    private static Macro macro(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Macro(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("name"),
                rs.getInt("current"),
                rs.getString("body"),
                rs.getString("set_status"),
                rs.getString("add_tag"),
                rs.getString("set_priority"),
                rs.getTimestamp("written_at").toInstant(),
                rs.getLong("version"));
    }

    AdminListing.Page<Macro> page(
            @Nullable String type, @Nullable String sort, @Nullable String cursor, @Nullable Integer limit) {
        return LIST.page(jdbc, AdminListing.Asked.of(sort, "type", type), cursor, limit, Macros::macro);
    }

    Macro find(UUID id) {
        return jdbc.sql("""
                        select m.*, v.body, v.set_status, v.add_tag, v.set_priority, v.written_at
                        from queues.macros m join queues.macro_versions v on v.macro_id = m.id and v.number = m.current
                        where m.id = :id and m.retired_at is null
                        """)
                .param("id", id)
                .query(Macros::macro)
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such macro."));
    }

    private WorkQueue queueOf(String type) {
        return queues.stream()
                .filter(queue -> queue.type().equals(type) && queue.replies())
                .findFirst()
                .or(() -> queues.stream()
                        .filter(queue -> queue.type().equals(type))
                        .findFirst())
                .orElseThrow(() -> ApiProblem.invalid("type", "isn't a kind of work."));
    }

    /** What makes a macro's words and changes wrong, before it's kept. */
    private void check(String type, Words words) {
        var queue = queueOf(type);
        if (words.body() != null && !words.body().isBlank()) {
            if (!queue.replies()) {
                throw ApiProblem.invalid("body", "this kind of work can't answer anyone; leave the words out.");
            }
            var found = VARIABLE.matcher(words.body());
            while (found.find()) {
                if (!VARIABLES.contains(found.group(1))) {
                    throw ApiProblem.invalid(
                            "body",
                            "{{" + found.group(1) + "}} isn't a variable. They are firstName, amount and link.");
                }
            }
        }
        if (words.setStatus() != null && !queue.hasStatus()) {
            throw ApiProblem.invalid("setStatus", "this kind of work has no state to set.");
        }
        if (words.setPriority() != null && !QueueTimers.OVERRIDE.containsKey(words.setPriority())) {
            throw ApiProblem.invalid("setPriority", "must be urgent, high, normal or low.");
        }
    }

    private static AuditTrail.Actor actor(UUID admin) {
        return new AuditTrail.Actor(AuditTrail.Actor.Kind.ADMIN, admin, admin.toString());
    }

    @Transactional
    Macro create(UUID admin, String type, String name, Words words) {
        check(type, words);
        var id = ids.newId();
        var now = Timestamp.from(clock.instant());
        jdbc.sql("insert into queues.macros (id, type, name, current, created_at) values (:id, :type, :name, 1, :now)")
                .param("id", id)
                .param("type", type)
                .param("name", name.strip())
                .param("now", now)
                .update();
        version(id, 1, words, admin, now);
        audit.record(new AuditTrail.Entry(
                actor(admin), "macro.create", "macro", id.toString(), "wrote the macro", null, Map.of("version", 1)));
        return find(id);
    }

    @Transactional
    Macro edit(UUID admin, UUID id, Words words) {
        var before = find(id);
        check(before.type(), words);
        var number = before.number() + 1;
        var now = Timestamp.from(clock.instant());
        version(id, number, words, admin, now);
        jdbc.sql("update queues.macros set current = :number where id = :id")
                .param("number", number)
                .param("id", id)
                .update();
        audit.record(new AuditTrail.Entry(
                actor(admin),
                "macro.edit",
                "macro",
                id.toString(),
                "edited the macro",
                Map.of("version", before.number()),
                Map.of("version", number)));
        return find(id);
    }

    private void version(UUID id, int number, Words words, UUID admin, Timestamp now) {
        jdbc.sql("""
                        insert into queues.macro_versions
                            (macro_id, number, body, set_status, add_tag, set_priority, written_by, written_at)
                        values (:id, :number, :body, :status, :tag, :priority, :admin, :now)
                        """)
                .param("id", id)
                .param("number", number)
                .param("body", words.body() == null || words.body().isBlank() ? null : words.body())
                .param("status", words.setStatus())
                .param("tag", words.addTag())
                .param("priority", words.setPriority())
                .param("admin", admin)
                .param("now", now)
                .update();
    }

    @Transactional
    void retire(UUID admin, UUID id) {
        var before = find(id);
        jdbc.sql("update queues.macros set retired_at = :now where id = :id")
                .param("now", Timestamp.from(clock.instant()))
                .param("id", id)
                .update();
        audit.record(new AuditTrail.Entry(
                actor(admin),
                "macro.retire",
                "macro",
                id.toString(),
                "retired the macro",
                Map.of("version", before.number()),
                null));
    }

    /** The words as this item's person would read them; an unknown value reads as a plain word, never a blank. */
    String render(Macro macro, QueueItems.Row item) {
        if (macro.body() == null) {
            return "";
        }
        var found = VARIABLE.matcher(macro.body());
        return found.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                switch (match.group(1)) {
                    case "firstName" -> firstName(item.person());
                    case "amount" -> Rupees.format(item.moneyPaise());
                    case "link" -> properties.webAppUrl() + "/account/help/";
                    default -> match.group();
                }));
    }

    private String firstName(@Nullable UUID person) {
        if (person == null) {
            return "there";
        }
        return users.displayName(person)
                .map(name -> name.strip().split("\\s+")[0])
                .filter(name -> !name.isBlank())
                .orElse("there");
    }

    /** What applying this would change, and applying it: its words through the module's reply, then the changes, audited with its version. */
    void apply(UUID admin, QueueItems.Row item, Macro macro) {
        if (!macro.type().equals(item.type())) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This macro is for another kind of work.");
        }
        var queue = queueOf(item.type());
        if (macro.body() != null) {
            queue.reply(
                    item.sourceId(),
                    render(macro, item),
                    Objects.requireNonNullElse(macro.setStatus(), "waiting"),
                    admin);
        } else if (macro.setStatus() != null) {
            queue.status(item.sourceId(), macro.setStatus(), "macro " + macro.name(), admin);
        }
        transactions.executeWithoutResult(status -> {
            if (macro.addTag() != null && item.person() != null) {
                tagged.add("user", item.person(), macro.addTag(), admin);
            }
            if (macro.setPriority() != null) {
                items.writePriority(item, macro.setPriority(), "macro " + macro.name());
            }
        });
        sync.refresh(item.type(), item.sourceId());
        audited(admin, item, macro);
    }

    private void audited(UUID admin, QueueItems.Row item, Macro macro) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("update queues.items set last_action_at = :now, version = version + 1 where id = :id")
                    .param("now", Timestamp.from(clock.instant()))
                    .param("id", item.id())
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor(admin),
                    "macro.apply",
                    "queue_item",
                    item.id().toString(),
                    "applied " + macro.name(),
                    null,
                    Map.of("macro", macro.id(), "version", macro.number())));
        });
    }
}
