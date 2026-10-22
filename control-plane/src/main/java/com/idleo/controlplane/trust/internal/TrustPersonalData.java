package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.PersonalData;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What trust holds about a renter (ADR 0037, docs/reference/personal-data-map.md): the reports they made, the ratings they gave,
 * and the reviews of software no renter may run that their sessions raised (ADR 0038), with what each job did each minute.
 * A report or a review open stops the account being deleted until an admin has settled it. Erasure keeps a rating's stars for
 * the host's average and drops its author and comment, keeps a report as a money record without the person's words, and
 * keeps a review's signals but drops what the admin wrote about the person.
 */
@Component
class TrustPersonalData implements PersonalData {

    private final JdbcClient jdbc;
    private final ExportRows rows;

    TrustPersonalData(JdbcClient jdbc, ExportRows rows) {
        this.jdbc = jdbc;
        this.rows = rows;
    }

    @Override
    public String module() {
        return "trust";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of(
                group("reports", "trust.disputes", "created_at", userId),
                group("ratings", "trust.ratings", "rated_at", userId),
                group("abuse reviews", "trust.abuse_items", "created_at", userId));
    }

    private Group group(String label, String table, String time, UUID userId) {
        return jdbc.sql("select count(*), min(%s), max(%s) from %s where renter_id = :user"
                        .formatted(time, time, table))
                .param("user", userId)
                .query((rs, n) -> new Group(
                        "reports", label, rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .single();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public List<Part> export(UUID userId) {
        var params = Map.of("user", userId);
        return List.of(
                new Part("reports", "reports", rows.list(jdbc, """
                                select id, session_id, reason, details, state, created_at, resolution_reason,
                                       resolved_at, refund_paise
                                from trust.disputes where renter_id = :user order by created_at
                                """, params)),
                new Part(
                        "reports",
                        "ratings",
                        rows.list(
                                jdbc,
                                "select session_id, stars, comment, rated_at, changed_at from trust.ratings"
                                        + " where renter_id = :user order by rated_at",
                                params)),
                new Part("reports", "abuseReviews", rows.list(jdbc, """
                                select id, session_id, attempt, state, action, banned, created_at, resolved_at, resolution,
                                       refund_paise
                                from trust.abuse_items where renter_id = :user order by created_at
                                """, params)),
                new Part("reports", "abuseSignals", rows.list(jdbc, """
                                select s.item_id, s.kind, s.detail, s.sha256, s.count, s.stopped, s.at
                                from trust.abuse_signals s join trust.abuse_items i on i.id = s.item_id
                                where i.renter_id = :user order by s.at
                                """, params)),
                new Part("reports", "abuseNotes", rows.list(jdbc, """
                                select n.item_id, n.note, n.at
                                from trust.abuse_notes n join trust.abuse_items i on i.id = n.item_id
                                where i.renter_id = :user order by n.at
                                """, params)),
                new Part("reports", "behaviour", rows.list(jdbc, """
                                select session_id, attempt, minute_index, util_pct, memory_pct, pcie_kb_s, net_out, net_in,
                                       printed, conn_drops, at
                                from trust.behavior_samples
                                where session_id in (select session_id from trust.ended_sessions where renter_id = :user)
                                order by session_id, attempt, minute_index
                                """, params)));
    }

    @Override
    public List<Blocker> blockers(UUID userId) {
        var open = jdbc.sql("select count(*) from trust.disputes where renter_id = :user and state = 'open'")
                .param("user", userId)
                .query(Long.class)
                .single();
        var blockers = new java.util.ArrayList<Blocker>();
        if (open > 0) {
            blockers.add(new Blocker(
                    "open-report",
                    "You have a report we haven't answered yet. We'll answer it soon; you can delete your account "
                            + "after that.",
                    null));
        }
        var reviewing = jdbc.sql("select count(*) from trust.abuse_items where renter_id = :user and state = 'open'")
                .param("user", userId)
                .query(Long.class)
                .single();
        if (reviewing > 0) {
            blockers.add(new Blocker(
                    "open-review",
                    "We're looking at a session of yours that ran software that isn't allowed on IdleO. You can delete "
                            + "your account when we've answered, and you can write to us about it in Help.",
                    null));
        }
        return blockers;
    }

    @Override
    @Transactional
    public void erase(UUID userId, ErasureStep step) {
        if (step != ErasureStep.DE_IDENTIFY) {
            return;
        }
        jdbc.sql("""
                        update trust.disputes
                        set details = null,
                            resolution_reason = case when resolution_reason is null then null else '' end
                        where renter_id = :user and (details is not null or resolution_reason <> '')
                        """).param("user", userId).update();
        jdbc.sql("update trust.ratings set comment = null, renter_id = null where renter_id = :user")
                .param("user", userId)
                .update();
        // What an admin wrote about the person: the reasons they settled a review with, and their notes.
        jdbc.sql("""
                        update trust.abuse_items
                        set resolution = case when resolution is null then null else '' end
                        where renter_id = :user and resolution <> ''
                        """).param("user", userId).update();
        jdbc.sql("""
                        update trust.abuse_notes set note = ''
                        where note <> '' and item_id in (select id from trust.abuse_items where renter_id = :user)
                        """).param("user", userId).update();
        jdbc.sql("delete from trust.idempotency_keys where user_id = :user")
                .param("user", userId)
                .update();
    }
}
