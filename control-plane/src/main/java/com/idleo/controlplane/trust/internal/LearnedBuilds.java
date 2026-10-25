package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.hosts.Blocklists;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The builds admins have confirmed as abuse, waiting to go on the signed blocklist (ADR 0038, ADR 0041). Only a
 * <em>confirmed</em> review item can add one, and only a build the item saw: a flagged program's executable, which the
 * runner hashes when its name is listed. Each add and each removal is audited with the admin's reason.
 */
@Service
class LearnedBuilds {

    /** One build, and whether the published list already names it. */
    record Addition(
            UUID id,
            UUID itemId,
            String sha256,
            String name,
            String kind,
            String reason,
            UUID addedBy,
            Instant createdAt,
            boolean published) {}

    /** What an add or a removal came to. */
    sealed interface Result {
        record Done() implements Result {}

        record NotFound() implements Result {}

        record Refused(String why) implements Result {}
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AbuseReviews reviews;
    private final AuditTrail audit;
    private final Blocklists published;
    private final IdGenerator ids;
    private final Clock clock;

    LearnedBuilds(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            AbuseReviews reviews,
            AuditTrail audit,
            Blocklists published,
            IdGenerator ids,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.reviews = reviews;
        this.audit = audit;
        this.published = published;
        this.ids = ids;
        this.clock = clock;
    }

    /** Adds the build {@code sha256} that the confirmed item {@code itemId} saw, as a {@code kind} of abuse. */
    Result add(UUID itemId, String sha256, String kind, String reason, AuditTrail.Actor actor, UUID adminId) {
        var item = reviews.find(itemId);
        if (item.isEmpty()) {
            return new Result.NotFound();
        }
        if (!item.get().state().equals("confirmed")) {
            return new Result.Refused("Only a confirmed review can add a program to the blocklist.");
        }
        var seen = reviews.signals(itemId).stream()
                .filter(signal ->
                        signal.kind().equals("process_name") && signal.sha256().equals(sha256))
                .findFirst();
        if (seen.isEmpty()) {
            return new Result.Refused(
                    "That isn't a build this review saw. Only a flagged program's build can be added.");
        }
        if (reviews.signals(itemId).stream()
                .anyMatch(signal ->
                        signal.kind().equals("process_hash") && signal.sha256().equals(sha256))) {
            return new Result.Refused(
                    "That build is already on the blocklist: it is the one that stopped this session.");
        }
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.sql("""
                                insert into trust.blocklist_additions
                                    (id, item_id, sha256, name, kind, reason, added_by, created_at)
                                values (:id, :item, :sha256, :name, :kind, :reason, :by, :now)
                                """)
                        .param("id", ids.newId())
                        .param("item", itemId)
                        .param("sha256", sha256)
                        .param("name", seen.get().detail())
                        .param("kind", kind)
                        .param("reason", reason)
                        .param("by", adminId)
                        .param("now", Timestamp.from(Instant.now(clock)))
                        .update();
                audit.record(new AuditTrail.Entry(
                        actor,
                        "abuse.add_to_blocklist",
                        "abuse_item",
                        itemId.toString(),
                        reason,
                        null,
                        Map.of("sha256", sha256, "name", seen.get().detail(), "kind", kind)));
            });
        } catch (DuplicateKeyException already) {
            return new Result.Refused("That build is already waiting to go on the blocklist.");
        }
        return new Result.Done();
    }

    /** Takes a build back off: the next signed file leaves it out, and agents take that file like any newer one. */
    Result remove(UUID id, String reason, AuditTrail.Actor actor) {
        var found = jdbc.sql("select item_id, sha256, name from trust.blocklist_additions where id = :id")
                .param("id", id)
                .query((rs, n) -> Map.of(
                        "item", rs.getObject("item_id", UUID.class).toString(),
                        "sha256", rs.getString("sha256"),
                        "name", rs.getString("name")))
                .optional();
        if (found.isEmpty()) {
            return new Result.NotFound();
        }
        transactions.executeWithoutResult(status -> {
            jdbc.sql("delete from trust.blocklist_additions where id = :id")
                    .param("id", id)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    "abuse.remove_from_blocklist",
                    "abuse_item",
                    found.get().get("item"),
                    reason,
                    found.get(),
                    null));
        });
        return new Result.Done();
    }

    /** Every build, newest first, each marked once the published list names it. */
    List<Addition> list() {
        Set<String> listed = published.learnedBuilds();
        return jdbc.sql("select * from trust.blocklist_additions order by created_at desc, id desc")
                .query((rs, n) -> new Addition(
                        rs.getObject("id", UUID.class),
                        rs.getObject("item_id", UUID.class),
                        rs.getString("sha256"),
                        rs.getString("name"),
                        rs.getString("kind"),
                        rs.getString("reason"),
                        rs.getObject("added_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        listed.contains(rs.getString("sha256"))))
                .list();
    }

    /** The additions as {@code idleo-release blocklist --additions} reads them: {@code {sha256, name, kind, item}}. */
    List<Map<String, String>> export() {
        return list().stream()
                .sorted(java.util.Comparator.comparing(Addition::createdAt).thenComparing(Addition::id))
                .map(addition -> Map.of(
                        "sha256", addition.sha256(),
                        "name", addition.name(),
                        "kind", addition.kind(),
                        "item", addition.itemId().toString()))
                .toList();
    }

    /** One build, if it exists. */
    Optional<Addition> find(UUID id) {
        return list().stream().filter(addition -> addition.id().equals(id)).findFirst();
    }
}
