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
 * What trust holds about a renter (ADR 0037, docs/reference/personal-data-map.md): the reports they made and the ratings
 * they gave. A report open stops the account being deleted. Erasure keeps a rating's stars for the host's average and
 * drops its author and comment, and keeps a report as a money record without the person's words.
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
                group("ratings", "trust.ratings", "rated_at", userId));
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
                                params)));
    }

    @Override
    public List<Blocker> blockers(UUID userId) {
        var open = jdbc.sql("select count(*) from trust.disputes where renter_id = :user and state = 'open'")
                .param("user", userId)
                .query(Long.class)
                .single();
        return open == 0
                ? List.of()
                : List.of(new Blocker(
                        "open-report",
                        "You have a report we haven't answered yet. We'll answer it soon; you can delete your account "
                                + "after that.",
                        null));
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
        jdbc.sql("delete from trust.idempotency_keys where user_id = :user")
                .param("user", userId)
                .update();
    }
}
