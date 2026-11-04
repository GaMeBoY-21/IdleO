package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ContactDetails;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only writer of {@code identity.audit_log} (ADR 0027). Rows are only ever added: triggers refuse any change,
 * and the application's database user has no right to update, delete or truncate the table. A trigger chains each row
 * to the one before it (ADR 0051, {@link AuditChain}). An entry names people by id only (ADR 0037): one holding an email
 * or an IP address anywhere is refused, and the action with it. The address and browser the request came from are
 * columns of their own, read only by the audit log's readers.
 */
@Component
class AuditLog implements AuditTrail {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final IdGenerator ids;
    private final Clock clock;

    AuditLog(JdbcClient jdbc, JsonMapper json, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Actor admin(CurrentUser user) {
        return new Actor(Actor.Kind.ADMIN, user.id(), user.id().toString());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Entry entry) {
        if (entry.reason() == null || entry.reason().isBlank()) {
            throw new IllegalArgumentException("an audited action has a reason");
        }
        var actor = entry.actor();
        var before = toJson(entry.before());
        var after = toJson(entry.after());
        if (ContactDetails.found(actor.name())
                || ContactDetails.found(entry.reason())
                || ContactDetails.found(entry.targetId())
                || ContactDetails.found(before)
                || ContactDetails.found(after)) {
            throw new IllegalArgumentException("an audit entry names people by id only, never by email or address");
        }
        jdbc.sql("""
                        insert into identity.audit_log
                            (id, actor_kind, actor_user_id, actor_name, action, target_type, target_id, reason,
                             before, after, created_at, request_id, admin_session_id, ip, user_agent, approval_id)
                        values (:id, :kind, :userId, :name, :action, :targetType, :targetId, :reason,
                                cast(:before as jsonb), cast(:after as jsonb), :now, :requestId, :session,
                                cast(:ip as inet), :userAgent, :approval)
                        """)
                .param("id", ids.newId())
                .param("kind", actor.kind().name().toLowerCase(Locale.ROOT))
                .param("userId", actor.userId())
                .param("name", actor.name())
                .param("action", entry.action())
                .param("targetType", entry.targetType())
                .param("targetId", entry.targetId())
                .param("reason", entry.reason().strip())
                .param("before", before)
                .param("after", after)
                .param("now", Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)))
                .param("requestId", AdminRequestContext.requestId())
                .param("session", AdminRequestContext.adminSession())
                .param("ip", AdminRequestContext.address())
                .param("userAgent", AdminRequestContext.userAgent())
                .param("approval", ApprovalContext.current())
                .update();
    }

    private @Nullable String toJson(@Nullable Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }
}
