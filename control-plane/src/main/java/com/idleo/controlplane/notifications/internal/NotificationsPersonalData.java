package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.identity.UserDirectory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What notifications hold about a person (ADR 0037, docs/reference/personal-data-map.md): every message they were sent, in
 * the app and by email, and what they chose to be told. Erasure deletes all of it, and a bounce or complaint kept
 * for their address; the last email after erasure is kept nowhere.
 */
@Component
class NotificationsPersonalData implements PersonalData {

    private final JdbcClient jdbc;
    private final ExportRows rows;
    private final UserDirectory users;

    NotificationsPersonalData(JdbcClient jdbc, ExportRows rows, UserDirectory users) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.users = users;
    }

    @Override
    public String module() {
        return "notifications";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of(jdbc.sql("select count(*), min(created_at), max(created_at) from notifications.notifications"
                        + " where user_id = :user")
                .param("user", userId)
                .query((rs, n) -> new Group(
                        "messages",
                        "messages",
                        rs.getLong(1),
                        instant(rs.getTimestamp(2)),
                        instant(rs.getTimestamp(3))))
                .single());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public List<Part> export(UUID userId) {
        var params = Map.of("user", userId);
        return List.of(
                new Part("messages", "messages", rows.list(jdbc, """
                                select kind, title, body, link, created_at, read_at, email_sent_at
                                from notifications.notifications where user_id = :user order by created_at, id
                                """, params)),
                new Part(
                        "messages",
                        "choices",
                        rows.list(
                                jdbc,
                                "select kind, email, in_app, updated_at from notifications.preferences"
                                        + " where user_id = :user order by kind",
                                params)));
    }

    @Override
    @Transactional
    public void erase(UUID userId, ErasureStep step) {
        switch (step) {
            // While the address is still the account's: what the email provider said about it goes.
            case LOCK ->
                users.verifiedEmail(userId)
                        .ifPresent(address -> jdbc.sql(
                                        "delete from notifications.email_suppressions where address_hash = :hash")
                                .param("hash", EmailSuppressions.hash(address))
                                .update());
            case DELETE_RECORDS -> {
                for (var table : List.of("notifications.notifications", "notifications.preferences")) {
                    jdbc.sql("delete from " + table + " where user_id = :user")
                            .param("user", userId)
                            .update();
                }
            }
            case REMOVE_HOSTS, DELETE_FILES, DE_IDENTIFY -> {}
        }
    }
}
