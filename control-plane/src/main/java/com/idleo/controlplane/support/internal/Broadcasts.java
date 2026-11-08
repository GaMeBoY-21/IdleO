package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.SemanticVersion;
import com.idleo.controlplane.hosts.HostOwners;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.UserDirectory;
import com.idleo.controlplane.notifications.AccountEmails;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Broadcast notices (ADR 0056), the written notice (ADR 0037) for many: to every renter, every host's owner, the people
 * carrying a tag, or the owners whose agent runs a version in a range; at a time, in the app always and by email when
 * asked. A notice is claimed once when it's due, on one instance (the row's own update decides), and each person is
 * told once for it, however often a send is tried. Scheduling, cancelling and sending are each audited, sending with how
 * many it reached, never who by address.
 */
@Service
class Broadcasts {

    private static final Logger log = LoggerFactory.getLogger(Broadcasts.class);

    enum Audience {
        RENTERS,
        HOSTS,
        TAG,
        AGENT_VERSIONS;

        String db() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    record Broadcast(
            UUID id,
            String subject,
            String body,
            String audience,
            @Nullable String tag,
            @Nullable String minAgentVersion,
            @Nullable String maxAgentVersion,
            boolean email,
            Instant sendAt,
            String state,
            @Nullable Integer reached,
            UUID createdBy,
            String reason,
            Instant createdAt,
            @Nullable Instant sentAt,
            long version) {}

    static final AdminListing LIST = AdminListing.of("broadcasts", AdminPermission.BROADCASTS_SEND, false)
            .from("support.broadcasts")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sortedBy("createdAt", true)
            .filter("state", "state = cast(:state as text)")
            .csv("id", "id")
            .csv("subject", "subject")
            .csv("audience", "audience")
            .csv("email", "email")
            .csv("sendAt", "send_at")
            .csv("state", "state")
            .csv("reached", "reached")
            .csv("createdAt", "created_at")
            .build();

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final UserDirectory users;
    private final HostOwners owners;
    private final AdminTagged tagged;
    private final AccountEmails emails;

    Broadcasts(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            UserDirectory users,
            HostOwners owners,
            AdminTagged tagged,
            AccountEmails emails) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
        this.users = users;
        this.owners = owners;
        this.tagged = tagged;
        this.emails = emails;
    }

    /** What's asked for. */
    record Asked(
            String subject,
            String body,
            Audience audience,
            @Nullable String tag,
            @Nullable String minAgentVersion,
            @Nullable String maxAgentVersion,
            boolean email,
            @Nullable Instant sendAt,
            String reason) {}

    /** Refuses what can't be sent: before it's asked for, and again when it's approved. */
    void check(Asked asked) {
        var now = clock.instant();
        if (asked.sendAt() != null && asked.sendAt().isBefore(now.minusSeconds(60))) {
            throw ApiProblem.invalid("sendAt", "Choose now or a time in the future.");
        }
        if (asked.audience() == Audience.TAG
                && (asked.tag() == null || asked.tag().isBlank())) {
            throw ApiProblem.invalid("tag", "Say which tag.");
        }
        if (asked.audience() == Audience.AGENT_VERSIONS) {
            if (asked.minAgentVersion() == null && asked.maxAgentVersion() == null) {
                throw ApiProblem.invalid("minAgentVersion", "Give the lowest version, the highest, or both.");
            }
            for (var version : java.util.Arrays.asList(asked.minAgentVersion(), asked.maxAgentVersion())) {
                if (version != null && SemanticVersion.parse(version).isEmpty()) {
                    throw ApiProblem.invalid("minAgentVersion", "A version is like 0.4.1.");
                }
            }
        }
    }

    Broadcast schedule(Asked asked, AuditTrail.Actor actor) {
        check(asked);
        var now = clock.instant();
        var sendAt = asked.sendAt() == null || asked.sendAt().isBefore(now) ? now : asked.sendAt();
        var id = ids.newId();
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            insert into support.broadcasts (id, subject, body, audience, tag, min_agent_version,
                                max_agent_version, email, send_at, state, created_by, reason, created_at)
                            values (:id, :subject, :body, :audience, :tag, :min, :max, :email, :sendAt, 'scheduled', :by,
                                    :reason, :now)
                            """)
                    .param("id", id)
                    .param("subject", asked.subject().strip())
                    .param("body", asked.body().strip())
                    .param("audience", asked.audience().db())
                    .param("tag", asked.audience() == Audience.TAG ? asked.tag() : null)
                    .param("min", asked.audience() == Audience.AGENT_VERSIONS ? asked.minAgentVersion() : null)
                    .param("max", asked.audience() == Audience.AGENT_VERSIONS ? asked.maxAgentVersion() : null)
                    .param("email", asked.email())
                    .param("sendAt", Timestamp.from(sendAt))
                    .param("by", actor.userId())
                    .param("reason", asked.reason())
                    .param("now", Timestamp.from(now))
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    "broadcast.schedule",
                    "broadcast",
                    id.toString(),
                    asked.reason(),
                    null,
                    Map.of(
                            "audience", asked.audience().db(),
                            "email", asked.email(),
                            "sendAt", sendAt.toString())));
        });
        return find(id).orElseThrow();
    }

    Broadcast cancel(UUID id, String reason, AuditTrail.Actor actor) {
        transactions.executeWithoutResult(status -> {
            int cancelled = jdbc.sql("""
                            update support.broadcasts set state = 'cancelled' where id = :id and state = 'scheduled'
                            """).param("id", id).update();
            if (cancelled == 0) {
                throw ApiProblem.of(Problem.ACTION_REFUSED, "Only a notice that hasn't gone yet can be cancelled.");
            }
            audit.record(new AuditTrail.Entry(
                    actor,
                    "broadcast.cancel",
                    "broadcast",
                    id.toString(),
                    reason,
                    Map.of("state", "scheduled"),
                    Map.of("state", "cancelled")));
        });
        return find(id).orElseThrow();
    }

    /** Sends what's due; a notice another instance claimed first is its to send. */
    @Scheduled(fixedDelayString = "${support.broadcasts.send-every:30s}", initialDelayString = "PT20S")
    void sendDue() {
        try {
            var due = jdbc.sql("select id from support.broadcasts where state = 'scheduled' and send_at <= :now")
                    .param("now", Timestamp.from(clock.instant()))
                    .query(UUID.class)
                    .list();
            due.forEach(this::send);
        } catch (RuntimeException e) {
            log.warn("Sending broadcast notices failed; it runs again shortly", e);
        }
    }

    /** Sends one now if it's still scheduled: whether it was this call that sent it. */
    boolean send(UUID id) {
        var claimed = jdbc.sql("""
                        update support.broadcasts set state = 'sending' where id = :id and state = 'scheduled'
                        returning id
                        """).param("id", id).query(UUID.class).optional();
        if (claimed.isEmpty()) {
            return false;
        }
        var broadcast = find(id).orElseThrow();
        var people = audience(broadcast);
        for (var person : people) {
            emails.announce(
                    person,
                    "broadcast:" + id + ":" + person,
                    broadcast.subject(),
                    broadcast.body(),
                    "/notifications/",
                    broadcast.email());
        }
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            update support.broadcasts set state = 'sent', reached = :reached, sent_at = :now
                            where id = :id
                            """)
                    .param("reached", people.size())
                    .param("now", Timestamp.from(clock.instant()))
                    .param("id", id)
                    .update();
            audit.record(new AuditTrail.Entry(
                    AuditTrail.Actor.system("broadcasts"),
                    "broadcast.send",
                    "broadcast",
                    id.toString(),
                    broadcast.reason(),
                    Map.of("state", "scheduled"),
                    Map.of("state", "sent", "reached", people.size(), "email", broadcast.email())));
        });
        return true;
    }

    /** Who a notice reaches, each once. */
    List<UUID> audience(Broadcast broadcast) {
        return switch (broadcast.audience()) {
            case "renters" -> users.activeUsers();
            case "hosts" -> owners.ownersRunning(null, null);
            case "tag" -> {
                var subjects = tagged.subjects("user", broadcast.tag());
                yield subjects == null
                        ? List.of()
                        : subjects.stream().filter(users::exists).toList();
            }
            case "agent_versions" -> owners.ownersRunning(broadcast.minAgentVersion(), broadcast.maxAgentVersion());
            default -> throw new IllegalStateException("no such audience: " + broadcast.audience());
        };
    }

    java.util.Optional<Broadcast> find(UUID id) {
        return jdbc.sql("select * from support.broadcasts where id = :id")
                .param("id", id)
                .query(Broadcasts::row)
                .optional();
    }

    static Broadcast row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        var sent = rs.getTimestamp("sent_at");
        return new Broadcast(
                rs.getObject("id", UUID.class),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("audience"),
                rs.getString("tag"),
                rs.getString("min_agent_version"),
                rs.getString("max_agent_version"),
                rs.getBoolean("email"),
                rs.getTimestamp("send_at").toInstant(),
                rs.getString("state"),
                (Integer) rs.getObject("reached"),
                rs.getObject("created_by", UUID.class),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                sent == null ? null : sent.toInstant(),
                rs.getLong("version"));
    }
}
