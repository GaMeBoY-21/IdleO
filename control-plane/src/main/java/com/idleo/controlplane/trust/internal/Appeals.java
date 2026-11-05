package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.AdminNoticed;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.sessions.UserUploads;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appeals (ADR 0053): one per step, from the person's account or the email's link, with a few words and up to 3 files. It
 * lands in the inbox with the step's evidence and the history. Someone else decides it, unless there's no one else, when
 * that's recorded where anyone reading the decision will see it. A reversal goes through the same approved lift.
 */
@Component
class Appeals {

    /** The appeals, their filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("appeals", AdminPermission.ENFORCEMENT_READ, true)
            .from("trust.appeals")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sort("state", AdminListing.Type.TEXT, "state")
            .sortedBy("createdAt", true)
            .filter("state", "state = cast(:state as text)")
            .filter("user", "user_id = cast(:user as uuid)")
            .csv("id", "id")
            .csv("enforcementId", "enforcement_id")
            .csv("userId", "user_id")
            .csv("state", "state")
            .csv("decidedBy", "decided_by")
            .csv("sameReviewer", "same_reviewer")
            .csv("createdAt", "created_at")
            .csv("decidedAt", "decided_at")
            .build();

    record Appeal(
            UUID id,
            UUID enforcementId,
            UUID userId,
            @Nullable String body,
            List<UUID> attachments,
            String state,
            @Nullable UUID decidedBy,
            @Nullable String decisionReason,
            boolean sameReviewer,
            Instant createdAt,
            @Nullable Instant decidedAt,
            long version) {}

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final AdminAccess access;
    private final ApplicationEventPublisher events;
    private final Enforcements enforcements;
    private final UserUploads uploads;
    private final TransactionTemplate transactions;

    Appeals(
            JdbcClient jdbc,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            AdminAccess access,
            ApplicationEventPublisher events,
            Enforcements enforcements,
            UserUploads uploads,
            PlatformTransactionManager transactionManager) {
        this.uploads = uploads;
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
        this.access = access;
        this.events = events;
        this.enforcements = enforcements;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    static Appeal row(ResultSet rs, int n) throws SQLException {
        var attachments = (UUID[]) rs.getArray("attachments").getArray();
        return new Appeal(
                rs.getObject("id", UUID.class),
                rs.getObject("enforcement_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("body"),
                Arrays.asList(attachments),
                rs.getString("state"),
                rs.getObject("decided_by", UUID.class),
                rs.getString("decision_reason"),
                rs.getBoolean("same_reviewer"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("decided_at") == null
                        ? null
                        : rs.getTimestamp("decided_at").toInstant(),
                rs.getLong("version"));
    }

    Optional<Appeal> find(UUID id) {
        return jdbc.sql("select * from trust.appeals where id = :id")
                .param("id", id)
                .query(Appeals::row)
                .optional();
    }

    Optional<Appeal> ofEnforcement(UUID enforcement) {
        return jdbc.sql("select * from trust.appeals where enforcement_id = :id")
                .param("id", enforcement)
                .query(Appeals::row)
                .optional();
    }

    /** The person appeals their own step, once. Someone else's is as if it weren't there. */
    Appeal make(UUID user, UUID enforcementId, String body, List<UUID> attachments) {
        var found = enforcements
                .find(enforcementId)
                .filter(step -> step.userId().equals(user))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such decision."));
        if (found.state().equals("lifted")) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This has been lifted already.");
        }
        if (attachments.size() > 3) {
            throw ApiProblem.invalid("attachments", "At most 3 files.");
        }
        if (!attachments.stream().allMatch(upload -> uploads.owns(user, upload))) {
            throw ApiProblem.invalid("attachments", "isn't one of your files.");
        }
        if (ofEnforcement(enforcementId).isPresent()) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "You've appealed this already.");
        }
        var id = ids.newId();
        jdbc.sql("""
                        insert into trust.appeals (id, enforcement_id, user_id, body, attachments, state, created_at)
                        values (:id, :enforcement, :user, :body, :attachments, 'open', :now)
                        on conflict (enforcement_id) do nothing
                        """)
                .param("id", id)
                .param("enforcement", enforcementId)
                .param("user", user)
                .param("body", body.strip())
                .param("attachments", attachments.toArray(UUID[]::new))
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return ofEnforcement(enforcementId).orElseThrow();
    }

    /** The decider, checked: the enforcer may decide only when no one else eligible can, and that's then recorded. */
    private boolean sameReviewer(Appeal appeal, UUID admin) {
        var step = enforcements.find(appeal.enforcementId()).orElseThrow();
        if (!step.enforcedBy().equals(admin)) {
            return false;
        }
        var someoneElse = access.mentionable().stream()
                .anyMatch(other -> !other.id().equals(admin)
                        && access.permissions(other.id()).contains(AdminPermission.APPEALS_DECIDE));
        if (someoneElse) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "You took this step, so another admin must decide its appeal.");
        }
        return true;
    }

    private Appeal open(UUID id) {
        var appeal = find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such appeal."));
        if (!appeal.state().equals("open")) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "This appeal has been decided.");
        }
        return appeal;
    }

    /** Keeps the step. The person is told. */
    Appeal uphold(UUID id, String reason, AuditTrail.Actor actor) {
        var appeal = open(id);
        var same = sameReviewer(appeal, actor.userId());
        transactions.executeWithoutResult(status -> {
            decided(id, "upheld", reason, actor, same);
            audit.record(new AuditTrail.Entry(
                    actor,
                    "appeal.uphold",
                    "appeal",
                    id.toString(),
                    reason,
                    null,
                    Map.of("sameReviewer", same, "enforcement", appeal.enforcementId())));
            events.publishEvent(new AdminNoticed(
                    appeal.userId(),
                    "appeal-upheld:" + id,
                    "Your appeal: we've kept our decision",
                    "We looked at your appeal again and kept the decision. Why: " + reason,
                    Enforcements.appealLink(appeal.enforcementId())));
        });
        return find(id).orElseThrow();
    }

    /** Whether this admin may reverse it, checked before it's asked. It stays open until the lift is approved and runs. */
    Appeal askToReverse(UUID id, AuditTrail.Actor actor) {
        var appeal = open(id);
        sameReviewer(appeal, actor.userId());
        return appeal;
    }

    /** The approved lift, and the appeal's outcome with it, told to the person. */
    void reversed(UUID id, String reason, AuditTrail.Actor actor) {
        var appeal = open(id);
        var same = sameReviewer(appeal, actor.userId());
        transactions.executeWithoutResult(status -> {
            enforcements.lift(appeal.enforcementId(), reason, actor);
            decided(id, "reversed", reason, actor, same);
            audit.record(new AuditTrail.Entry(
                    actor,
                    "appeal.reverse",
                    "appeal",
                    id.toString(),
                    reason,
                    null,
                    Map.of("sameReviewer", same, "enforcement", appeal.enforcementId())));
            events.publishEvent(new AdminNoticed(
                    appeal.userId(),
                    "appeal-reversed:" + id,
                    "Your appeal: we've reversed our decision",
                    "We looked at your appeal again and reversed the decision. We're sorry for the trouble.",
                    "/account/"));
        });
    }

    private void decided(UUID id, String state, String reason, AuditTrail.Actor actor, boolean same) {
        jdbc.sql("""
                        update trust.appeals
                        set state = :state, decided_by = :by, decision_reason = :reason, same_reviewer = :same,
                            decided_at = :now
                        where id = :id
                        """)
                .param("state", state)
                .param("by", actor.userId())
                .param("reason", reason)
                .param("same", same)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", id)
                .update();
    }
}
