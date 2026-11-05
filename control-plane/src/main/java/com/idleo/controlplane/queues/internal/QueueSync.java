package com.idleo.controlplane.queues.internal;

import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.OneAtATime;
import com.idleo.controlplane.WorkQueue;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings the inbox up to date with what each module says is open (ADR 0053): new items, timers and priorities, pauses,
 * breaches, what's been resolved, and claims left idle. A module that can't answer changes nothing: never read as empty.
 */
@Component
class QueueSync {

    private static final Logger log = LoggerFactory.getLogger(QueueSync.class);

    private final Map<String, List<WorkQueue>> byType;
    private final JdbcClient jdbc;
    private final AdminTagged tagged;
    private final OneAtATime oneAtATime;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final QueueProperties properties;
    private final QueueMetrics metrics;
    private final TransactionTemplate transactions;

    QueueSync(
            List<WorkQueue> queues,
            JdbcClient jdbc,
            AdminTagged tagged,
            OneAtATime oneAtATime,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            QueueProperties properties,
            QueueMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this.byType = queues.stream().collect(Collectors.groupingBy(WorkQueue::type));
        this.jdbc = jdbc;
        this.tagged = tagged;
        this.oneAtATime = oneAtATime;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
        this.properties = properties;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${queues.sync-every}")
    void scheduled() {
        oneAtATime.run("queues:sync", this::run);
    }

    /** One pass over every kind of work. */
    void run() {
        var vip = people("vip");
        var fraud = people("fraud-suspect");
        var targets = targets();
        byType.forEach((type, queues) -> {
            try {
                var open =
                        queues.stream().flatMap(queue -> queue.open().stream()).toList();
                transactions.executeWithoutResult(status -> apply(type, open, null, targets, vip, fraud));
            } catch (RuntimeException failed) {
                log.error("The {} queue couldn't be read; its items stay as they were", type, failed);
                metrics.failed(type);
            }
        });
        releaseIdle();
        metrics.refresh();
    }

    /** One item again, after an action on it: {@code type} and its source are what the module calls it. */
    void refresh(String type, String sourceId) {
        var targets = targets();
        var found = byType.getOrDefault(type, List.of()).stream()
                .flatMap(queue -> queue.find(sourceId).stream())
                .toList();
        transactions.executeWithoutResult(
                status -> apply(type, found, sourceId, targets, people("vip"), people("fraud-suspect")));
    }

    private Set<UUID> people(String tag) {
        var found = tagged.subjects("user", tag);
        return found == null ? Set.of() : new HashSet<>(found);
    }

    private Map<String, QueueTimers.Target> targets() {
        return jdbc
                .sql("select * from queues.sla_targets")
                .query((rs, n) -> Map.entry(
                        rs.getString("sla"),
                        new QueueTimers.Target(
                                rs.getObject("first_minutes", Integer.class),
                                rs.getObject("resolve_minutes", Integer.class),
                                rs.getBoolean("pauses"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private record Kept(
            UUID id,
            String sourceId,
            @Nullable Instant firstReplyAt,
            @Nullable Instant pausedSince,
            long pausedMs,
            @Nullable Instant dueAt,
            @Nullable Instant breachedAt,
            @Nullable Integer override,
            Instant openedAt) {}

    private void apply(
            String type,
            List<WorkQueue.Item> open,
            @Nullable String onlySource,
            Map<String, QueueTimers.Target> targets,
            Set<UUID> vip,
            Set<UUID> fraud) {
        var now = clock.instant();
        var kept = jdbc
                .sql("""
                        select * from queues.items where type = :type and state <> 'resolved'
                          and (cast(:only as text) is null or source_id = cast(:only as text))
                        """)
                .param("type", type)
                .param("only", onlySource)
                .query((rs, n) -> new Kept(
                        rs.getObject("id", UUID.class),
                        rs.getString("source_id"),
                        instant(rs.getTimestamp("first_reply_at")),
                        instant(rs.getTimestamp("paused_since")),
                        rs.getLong("paused_ms"),
                        instant(rs.getTimestamp("due_at")),
                        instant(rs.getTimestamp("breached_at")),
                        rs.getObject("override_score", Integer.class),
                        rs.getTimestamp("opened_at").toInstant()))
                .list()
                .stream()
                .collect(Collectors.toMap(Kept::sourceId, Function.identity()));
        var seen = new HashSet<String>();
        for (var item : open) {
            seen.add(item.sourceId());
            upsert(type, item, kept.get(item.sourceId()), targets, vip, fraud, now);
        }
        kept.forEach((source, item) -> {
            if (!seen.contains(source)) {
                resolve(type, item, now);
            }
        });
    }

    private void upsert(
            String type,
            WorkQueue.Item item,
            @Nullable Kept before,
            Map<String, QueueTimers.Target> targets,
            Set<UUID> vip,
            Set<UUID> fraud,
            Instant now) {
        var target = targets.getOrDefault(item.sla(), new QueueTimers.Target(null, null, false));
        var pausing = target.pauses() && item.waiting();
        var pausedMs = before == null ? 0 : before.pausedMs();
        var pausedSince = before == null ? null : before.pausedSince();
        if (pausing && pausedSince == null) {
            pausedSince = now;
        } else if (!pausing && pausedSince != null) {
            pausedMs += Duration.between(pausedSince, now).toMillis();
            pausedSince = null;
        }
        var counted = pausedMs
                + (pausedSince == null ? 0 : Duration.between(pausedSince, now).toMillis());
        var person = item.person();
        var timer = QueueTimers.of(
                new QueueTimers.Facts(
                        target,
                        item.openedAt(),
                        item.dueAt(),
                        item.firstReplyAt(),
                        pausing,
                        counted,
                        item.moneyPaise(),
                        item.runningSession(),
                        person != null && vip.contains(person),
                        person != null && fraud.contains(person),
                        before == null ? null : before.override(),
                        properties.chargebackPageBefore()),
                now);
        var breachedAt = before == null ? null : before.breachedAt();
        if (breachedAt == null && timer.dueAt() != null && now.isAfter(timer.dueAt())) {
            breachedAt = now;
            metrics.breached(type);
        }
        if (before != null && before.firstReplyAt() == null && item.firstReplyAt() != null) {
            metrics.firstReply(type, Duration.between(item.openedAt(), item.firstReplyAt()));
        }
        jdbc.sql("""
                        insert into queues.items (id, type, source_id, subject_kind, subject_id, person_id, title, link,
                            sla, opened_at, state, first_reply_at, paused_since, paused_ms, due_at, soon_at, breached_at,
                            money_paise, running_session, score, sort_key, last_action_at, created_at)
                        values (:id, :type, :source, :subjectKind, :subject, :person, :title, :link, :sla, :opened,
                            case when :waiting then 'waiting' else 'open' end, :firstReply, :pausedSince, :pausedMs, :due,
                            :soon, :breached, :money, :running, :score, :sortKey, :activity, :now)
                        on conflict (type, source_id) do update set
                            title = excluded.title, link = excluded.link, sla = excluded.sla,
                            subject_kind = excluded.subject_kind, subject_id = excluded.subject_id,
                            person_id = excluded.person_id,
                            state = case
                                when :waiting then 'waiting'
                                when queues.items.assignee_id is not null then 'claimed'
                                else 'open' end,
                            resolved_at = null,
                            first_reply_at = excluded.first_reply_at, paused_since = excluded.paused_since,
                            paused_ms = excluded.paused_ms, due_at = excluded.due_at, soon_at = excluded.soon_at,
                            breached_at = excluded.breached_at, money_paise = excluded.money_paise,
                            running_session = excluded.running_session, score = excluded.score,
                            sort_key = excluded.sort_key,
                            last_action_at = greatest(queues.items.last_action_at, excluded.last_action_at)
                        """)
                .param("id", before == null ? ids.newId() : before.id())
                .param("type", type)
                .param("source", item.sourceId())
                .param("subjectKind", item.subjectKind())
                .param("subject", item.subjectId())
                .param("person", person)
                .param("title", item.title())
                .param("link", item.link())
                .param("sla", item.sla())
                .param("opened", Timestamp.from(item.openedAt()))
                .param("waiting", item.waiting())
                .param("firstReply", time(item.firstReplyAt()))
                .param("pausedSince", time(pausedSince))
                .param("pausedMs", pausedMs)
                .param("due", time(timer.dueAt()))
                .param("soon", time(timer.soonAt()))
                .param("breached", time(breachedAt))
                .param("money", item.moneyPaise())
                .param("running", item.runningSession())
                .param("score", timer.score())
                .param("sortKey", timer.sortKey())
                .param("activity", time(item.activityAt()))
                .param("now", Timestamp.from(now))
                .update();
    }

    private void resolve(String type, Kept gone, Instant now) {
        jdbc.sql("""
                        update queues.items
                        set state = 'resolved', resolved_at = :now, assignee_id = null, paused_since = null, due_at = null,
                            soon_at = null, version = version + 1
                        where id = :id
                        """).param("now", Timestamp.from(now)).param("id", gone.id()).update();
        metrics.resolved(type, Duration.between(gone.openedAt(), now));
    }

    /** A claim with no action for the idle time goes back to everyone, and says so in the audit log. */
    private void releaseIdle() {
        var now = clock.instant();
        var cutoff = Timestamp.from(now.minus(properties.idleClaim()));
        transactions.executeWithoutResult(status -> {
            var released = jdbc.sql("""
                            update queues.items
                            set assignee_id = null, claimed_at = null, state = 'open', version = version + 1,
                                last_action_at = :now
                            where state = 'claimed' and last_action_at < :cutoff
                            returning id, assignee_id
                            """)
                    .param("now", Timestamp.from(now))
                    .param("cutoff", cutoff)
                    .query((rs, n) -> rs.getObject("id", UUID.class))
                    .list();
            released.forEach(id -> audit.record(new AuditTrail.Entry(
                    AuditTrail.Actor.system("the inbox"),
                    "queue.claim_released",
                    "queue_item",
                    id.toString(),
                    "no action for " + properties.idleClaim().toMinutes() + " minutes",
                    null,
                    Map.of("assignee", "none"))));
        });
    }

    private static @Nullable Instant instant(@Nullable Timestamp at) {
        return at == null ? null : at.toInstant();
    }

    private static @Nullable Timestamp time(@Nullable Instant at) {
        return at == null ? null : Timestamp.from(at);
    }
}
