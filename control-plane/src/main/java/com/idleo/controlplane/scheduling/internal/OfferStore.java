package com.idleo.controlplane.scheduling.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@code scheduling.offers}: up to three per session. */
@Component
class OfferStore {

    private final JdbcClient jdbc;

    OfferStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Offer(
            UUID id,
            UUID sessionId,
            UUID hostId,
            int attemptNumber,
            String status,
            @Nullable String declineReason,
            Instant expiresAt,
            Instant createdAt) {}

    void insert(Offer offer) {
        jdbc.sql("""
                        insert into scheduling.offers
                            (id, session_id, host_id, attempt_number, status, expires_at, created_at)
                        values (:id, :sessionId, :hostId, :attempt, 'pending', :expiresAt, :createdAt)
                        """)
                .param("id", offer.id())
                .param("sessionId", offer.sessionId())
                .param("hostId", offer.hostId())
                .param("attempt", offer.attemptNumber())
                .param("expiresAt", Timestamp.from(offer.expiresAt()))
                .param("createdAt", Timestamp.from(offer.createdAt()))
                .update();
    }

    /** The offer, locked for the rest of the transaction. */
    Optional<Offer> lock(UUID offerId) {
        return jdbc.sql("select * from scheduling.offers where id = :id for update")
                .param("id", offerId)
                .query(OfferStore::offer)
                .optional();
    }

    int count(UUID sessionId) {
        return jdbc.sql("select count(*) from scheduling.offers where session_id = :sessionId")
                .param("sessionId", sessionId)
                .query(Integer.class)
                .single();
    }

    List<UUID> hostsOffered(UUID sessionId) {
        return jdbc.sql("select host_id from scheduling.offers where session_id = :sessionId")
                .param("sessionId", sessionId)
                .query(UUID.class)
                .list();
    }

    void answer(UUID offerId, String status, @Nullable String declineReason, Instant at) {
        jdbc.sql("""
                        update scheduling.offers set status = :status, decline_reason = :reason, answered_at = :at
                        where id = :id
                        """)
                .param("status", status)
                .param("reason", declineReason)
                .param("at", Timestamp.from(at))
                .param("id", offerId)
                .update();
    }

    /** Pending offers past their expiry, locked; offers another instance is handling are skipped. */
    List<Offer> lockExpired(Instant now, int limit) {
        return jdbc.sql("""
                        select * from scheduling.offers
                        where status = 'pending' and expires_at <= :now
                        order by expires_at
                        limit :limit
                        for update skip locked
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(OfferStore::offer)
                .list();
    }

    int withdrawPending(UUID sessionId, Instant at) {
        return jdbc.sql("""
                        update scheduling.offers set status = 'withdrawn', answered_at = :at
                        where session_id = :sessionId and status = 'pending'
                        """)
                .param("at", Timestamp.from(at))
                .param("sessionId", sessionId)
                .update();
    }

    private static Offer offer(ResultSet rs, int row) throws SQLException {
        return new Offer(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("host_id", UUID.class),
                rs.getInt("attempt_number"),
                rs.getString("status"),
                rs.getString("decline_reason"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
