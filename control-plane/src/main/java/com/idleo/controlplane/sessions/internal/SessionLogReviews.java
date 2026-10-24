package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.identity.AuditTrail;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An admin reading a session's log (ADR 0039): to judge a renter's report, or to gather a chargeback's evidence. The
 * log is the renter's own job's output, so each read is audited with why, once for its first page; the chunks are the
 * same, in the same order, as the renter's {@code GET /v1/sessions/{id}/logs}.
 */
@Component
class SessionLogReviews {

    private final SessionLogs logs;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    SessionLogReviews(
            SessionLogs logs, AuditTrail audit, JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.logs = logs;
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Up to {@code limit} chunks after {@code cursor} (null: from the start, audited), or empty when there's no such session. */
    Optional<SessionLogs.Page> read(
            UUID sessionId, @Nullable String cursor, int limit, String reason, AuditTrail.Actor actor) {
        boolean exists = Objects.requireNonNull(transactions.execute(status -> {
            boolean found = jdbc.sql("select exists (select 1 from sessions.sessions where id = :id)")
                    .param("id", sessionId)
                    .query(Boolean.class)
                    .single();
            if (found && cursor == null) {
                audit.record(new AuditTrail.Entry(
                        actor, "session.log_read", "session", sessionId.toString(), reason, null, Map.of()));
            }
            return found;
        }));
        if (!exists) {
            return Optional.empty();
        }
        var after = cursor == null ? new int[] {1, 0} : Cursors.chunk(cursor);
        return Optional.of(logs.page(sessionId, after[0], after[1], limit));
    }
}
