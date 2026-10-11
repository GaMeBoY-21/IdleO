package com.idleo.controlplane.notifications.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a person chose to be told about (ADR 0024). Everything is on unless they turned it off, and only
 * the choices that differ from that are stored, so a new kind starts on for everyone.
 */
@Service
class NotificationPreferences {

    /** One kind's two switches. */
    record Choice(boolean email, boolean inApp) {
        static final Choice ON = new Choice(true, true);
    }

    private record Stored(String kind, Choice choice) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    NotificationPreferences(JdbcClient jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Every kind, with this person's choice or the default. */
    Map<NotificationKind, Choice> read(UUID userId) {
        var choices = new EnumMap<NotificationKind, Choice>(NotificationKind.class);
        for (var kind : NotificationKind.values()) {
            choices.put(kind, Choice.ON);
        }
        jdbc.sql("select kind, email, in_app from notifications.preferences where user_id = :user")
                .param("user", userId)
                .query((rs, row) ->
                        new Stored(rs.getString("kind"), new Choice(rs.getBoolean("email"), rs.getBoolean("in_app"))))
                .list()
                .forEach(stored ->
                        NotificationKind.fromSlug(stored.kind()).ifPresent(kind -> choices.put(kind, stored.choice())));
        return choices;
    }

    /** Replaces all of this person's choices; a kind left out is back on. Locked kinds must be on. */
    Map<NotificationKind, Choice> replace(UUID userId, Map<NotificationKind, Choice> choices) {
        var now = Timestamp.from(Instant.now(clock));
        transactions.executeWithoutResult(status -> {
            jdbc.sql("delete from notifications.preferences where user_id = :user")
                    .param("user", userId)
                    .update();
            choices.forEach((kind, choice) -> {
                if (!choice.equals(Choice.ON)) {
                    jdbc.sql("""
                                    insert into notifications.preferences (user_id, kind, email, in_app, updated_at)
                                    values (:user, :kind, :email, :inApp, :now)
                                    """)
                            .param("user", userId)
                            .param("kind", kind.slug())
                            .param("email", choice.email())
                            .param("inApp", choice.inApp())
                            .param("now", now)
                            .update();
                }
            });
        });
        return read(userId);
    }
}
