package com.idleo.controlplane.notifications.internal;

import com.idleo.controlplane.IdGenerator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every message a person was told, with explicit SQL (ADR 0009). A message is made once for each event
 * ({@code dedupe_key}), so an event delivered twice changes nothing the second time.
 */
@Repository
class NotificationStore {

    /** One stored message. {@code emailSentAt} and {@code emailClaimedAt} say how far its email got. */
    record Stored(
            UUID id,
            UUID userId,
            String kind,
            Message message,
            Instant createdAt,
            boolean inApp,
            boolean emailWanted,
            @Nullable Instant readAt,
            @Nullable Instant emailClaimedAt,
            @Nullable Instant emailSentAt) {}

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;

    NotificationStore(JdbcClient jdbc, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    /** Makes the message, or finds the one this event already made. */
    Stored make(
            UUID userId, NotificationKind kind, String dedupeKey, Message message, boolean inApp, boolean emailWanted) {
        jdbc.sql("""
                        insert into notifications.notifications
                            (id, user_id, kind, dedupe_key, title, body, link, created_at, in_app, email_wanted)
                        values (:id, :user, :kind, :key, :title, :body, :link, :now, :inApp, :email)
                        on conflict (user_id, dedupe_key) do nothing
                        """)
                .param("id", ids.newId())
                .param("user", userId)
                .param("kind", kind.slug())
                .param("key", dedupeKey)
                .param("title", message.title())
                .param("body", message.body())
                .param("link", message.link())
                .param("now", Timestamp.from(Instant.now(clock)))
                .param("inApp", inApp)
                .param("email", emailWanted)
                .update();
        return jdbc.sql("select * from notifications.notifications where user_id = :user and dedupe_key = :key")
                .param("user", userId)
                .param("key", dedupeKey)
                .query(NotificationStore::stored)
                .single();
    }

    Optional<Stored> find(UUID id) {
        return jdbc.sql("select * from notifications.notifications where id = :id")
                .param("id", id)
                .query(NotificationStore::stored)
                .optional();
    }

    /** The person's in-app messages, newest first, after {@code after} when given. */
    List<Stored> page(UUID userId, @Nullable UUID after, int limit) {
        return jdbc.sql("""
                        select * from notifications.notifications
                        where user_id = :user and in_app and (cast(:after as uuid) is null or id < cast(:after as uuid))
                        order by id desc
                        limit :limit
                        """)
                .param("user", userId)
                .param("after", after)
                .param("limit", limit)
                .query(NotificationStore::stored)
                .list();
    }

    int unread(UUID userId) {
        return jdbc.sql("""
                        select count(*) from notifications.notifications
                        where user_id = :user and in_app and read_at is null
                        """).param("user", userId).query(Integer.class).single();
    }

    /** Marks this person's messages up to and including {@code upTo} read; ones already read keep their time. */
    /** Marks just this one of the person's messages read. */
    void markOneRead(UUID userId, UUID id) {
        jdbc.sql("""
                        update notifications.notifications set read_at = :now
                        where user_id = :user and in_app and read_at is null and id = :id
                        """)
                .param("now", Timestamp.from(Instant.now(clock)))
                .param("user", userId)
                .param("id", id)
                .update();
    }

    void markRead(UUID userId, UUID upTo) {
        jdbc.sql("""
                        update notifications.notifications set read_at = :now
                        where user_id = :user and in_app and read_at is null and id <= :upTo
                        """)
                .param("now", Timestamp.from(Instant.now(clock)))
                .param("user", userId)
                .param("upTo", upTo)
                .update();
    }

    /**
     * Takes the right to send this message's email: true for the one caller that gets it, and never again once
     * it was sent or while another holds it.
     */
    boolean claimEmail(UUID id) {
        return jdbc.sql("""
                                update notifications.notifications set email_claimed_at = :now
                                where id = :id and email_wanted and email_claimed_at is null and email_sent_at is null
                                """)
                        .param("now", Timestamp.from(Instant.now(clock)))
                        .param("id", id)
                        .update()
                == 1;
    }

    void markEmailSent(UUID id) {
        jdbc.sql("update notifications.notifications set email_sent_at = :now where id = :id")
                .param("now", Timestamp.from(Instant.now(clock)))
                .param("id", id)
                .update();
    }

    /** The mail server refused it: give the claim back, so the retry sends it. */
    void releaseEmail(UUID id) {
        jdbc.sql(
                        "update notifications.notifications set email_claimed_at = null where id = :id and email_sent_at is null")
                .param("id", id)
                .update();
    }

    private static Stored stored(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Stored(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("kind"),
                new Message(rs.getString("title"), rs.getString("body"), rs.getString("link")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("in_app"),
                rs.getBoolean("email_wanted"),
                instant(rs.getTimestamp("read_at")),
                instant(rs.getTimestamp("email_claimed_at")),
                instant(rs.getTimestamp("email_sent_at")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
