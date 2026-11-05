package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.PersonalDataAccess;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The access log (ADR 0051): who at IdleO viewed or revealed whose data, which field, why, when and from which session.
 * The only writer of {@code identity.personal_data_access}; a reveal also lands in the audit log. Kept a year, like the
 * security log, shown to whoever holds {@code audit.read}, and in the person's own export.
 */
@Component
class AccessLog implements PersonalDataAccess {

    /** The field of an entry for opening a person's page, which reveals nothing whole. */
    static final String VIEW = "view";

    /** The field of an entry for a search of the console, whose record is the text searched for, masked. */
    static final String SEARCH = "search";

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final AdminInsiderSignals insider;

    AccessLog(JdbcClient jdbc, IdGenerator ids, Clock clock, AuditTrail audit, AdminInsiderSignals insider) {
        this.insider = insider;
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
    }

    /** An admin opened this person's page. */
    void viewed(CurrentUser admin, UUID subject) {
        viewed(admin, subject, subject.toString());
    }

    @Override
    public void viewed(CurrentUser admin, UUID subject, String recordId) {
        write(admin.id(), subject, VIEW, recordId, "opened their page");
    }

    @Override
    public void read(CurrentUser admin, UUID subject, String field, String recordId, String reason) {
        write(admin.id(), subject, field, recordId, reason);
        insider.revealed(admin.id());
    }

    @Override
    public void searched(CurrentUser admin, String maskedQuery) {
        write(admin.id(), null, SEARCH, maskedQuery, "searched the console");
    }

    /** An admin read this field whole, with this reason. */
    @Transactional
    void revealed(CurrentUser admin, @Nullable UUID subject, String field, String recordId, String reason) {
        write(admin.id(), subject, field, recordId, reason);
        audit.record(new AuditTrail.Entry(
                audit.admin(admin),
                "personal_data.reveal",
                subject == null ? "record" : "user",
                subject == null ? recordId : subject.toString(),
                reason,
                null,
                Map.of("field", field)));
        insider.revealed(admin.id());
    }

    private void write(UUID admin, @Nullable UUID subject, String field, String recordId, String reason) {
        jdbc.sql("""
                        insert into identity.personal_data_access
                            (id, admin_id, subject_user_id, field, record_id, reason, admin_session_id, at)
                        values (:id, :admin, :subject, :field, :record, :reason, :session, :at)
                        """)
                .param("id", ids.newId())
                .param("admin", admin)
                .param("subject", subject)
                .param("field", field)
                .param("record", recordId)
                .param("reason", reason)
                .param("session", AdminRequestContext.adminSession())
                .param("at", Timestamp.from(Instant.now(clock)))
                .update();
    }
}
