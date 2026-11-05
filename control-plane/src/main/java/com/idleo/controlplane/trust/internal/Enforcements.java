package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.AccountLimits;
import com.idleo.controlplane.AccountWarnings;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.OneAtATime;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AccountHolds;
import com.idleo.controlplane.identity.AdminNoticed;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The enforcement ladder (ADR 0053): warn, limit, suspend for a time, ban, for renters and hosts separately, each from a
 * template citing the Terms. The person is told why, with a link to appeal. Stopping is immediate; lifting goes to another
 * admin; a strike stops counting after its time.
 */
@Component
class Enforcements implements AccountLimits, AccountWarnings {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Set<String> LIMITS = Set.of("no-custom-images", "session-hours-cap", "payouts-held");

    /** What's been done to accounts, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("enforcements", AdminPermission.ENFORCEMENT_READ, true)
            .select("e.*, t.title, t.clause, (a.id is not null) as appealed")
            .from("trust.enforcements e join trust.enforcement_templates t on t.id = e.template_id"
                    + " left join trust.appeals a on a.enforcement_id = e.id")
            .id("e.id")
            .sort("createdAt", AdminListing.Type.UUID, "e.id")
            .sort("step", AdminListing.Type.TEXT, "e.step")
            .sortedBy("createdAt", true)
            .filter("user", "e.user_id = cast(:user as uuid)")
            .filter("state", "e.state = cast(:state as text)")
            .filter("step", "e.step = cast(:step as text)")
            .csv("id", "e.id")
            .csv("userId", "e.user_id")
            .csv("role", "e.role")
            .csv("step", "e.step")
            .csv("template", "e.template_id")
            .csv("state", "e.state")
            .csv("reason", "e.reason")
            .csv("enforcedBy", "e.enforced_by")
            .csv("createdAt", "e.created_at")
            .csv("endsAt", "e.ends_at")
            .build();

    /** One step taken. */
    record Enforcement(
            UUID id,
            UUID userId,
            String role,
            String step,
            String template,
            String title,
            String clause,
            @Nullable String limitKind,
            @Nullable Integer limitHours,
            @Nullable Instant endsAt,
            Instant strikeExpiresAt,
            String state,
            @Nullable String reason,
            UUID enforcedBy,
            Instant createdAt,
            boolean appealed,
            long version) {}

    /** What's asked. {@code limitKind} and {@code limitHours} are for a limit; {@code endsAt} a suspension's, or a limit's. */
    record Asked(
            UUID userId,
            String template,
            @Nullable String limitKind,
            @Nullable Integer limitHours,
            @Nullable Instant endsAt,
            String reason) {}

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final AccountHolds holds;
    private final ApplicationEventPublisher events;
    private final OneAtATime oneAtATime;
    private final Duration strikeLasts;
    private final TransactionTemplate transactions;

    Enforcements(
            JdbcClient jdbc,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            AccountHolds holds,
            ApplicationEventPublisher events,
            OneAtATime oneAtATime,
            @Value("${trust.enforcement.strike-lasts}") Duration strikeLasts,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
        this.holds = holds;
        this.events = events;
        this.oneAtATime = oneAtATime;
        this.strikeLasts = strikeLasts;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    static Enforcement row(ResultSet rs, int n) throws SQLException {
        return new Enforcement(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("role"),
                rs.getString("step"),
                rs.getString("template_id"),
                rs.getString("title"),
                rs.getString("clause"),
                rs.getString("limit_kind"),
                rs.getObject("limit_hours", Integer.class),
                instant(rs, "ends_at"),
                rs.getTimestamp("strike_expires_at").toInstant(),
                rs.getString("state"),
                rs.getString("reason"),
                rs.getObject("enforced_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("appealed"),
                rs.getLong("version"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }

    private static final String ONE = """
            select e.*, t.title, t.clause, exists (select 1 from trust.appeals a where a.enforcement_id = e.id) as appealed
            from trust.enforcements e join trust.enforcement_templates t on t.id = e.template_id
            """;

    Optional<Enforcement> find(UUID id) {
        return jdbc.sql(ONE + " where e.id = :id")
                .param("id", id)
                .query(Enforcements::row)
                .optional();
    }

    List<Enforcement> of(UUID user) {
        return jdbc.sql(ONE + " where e.user_id = :user order by e.created_at desc")
                .param("user", user)
                .query(Enforcements::row)
                .list();
    }

    /** Strikes that still count, by role: the ladder is read from these. */
    long strikes(UUID user, String role) {
        return jdbc.sql("""
                        select count(*) from trust.enforcements
                        where user_id = :user and role = :role and state <> 'lifted' and strike_expires_at > :now
                        """)
                .param("user", user)
                .param("role", role)
                .param("now", Timestamp.from(clock.instant()))
                .query(Long.class)
                .single();
    }

    private record Template(String role, String step, String title, String body, String clause) {}

    private Optional<Template> template(String id) {
        return jdbc.sql("select * from trust.enforcement_templates where id = :id")
                .param("id", id)
                .query((rs, n) -> new Template(
                        rs.getString("role"),
                        rs.getString("step"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("clause")))
                .optional();
    }

    /** Takes the step now. Telling the person follows, outside it: a mail server's trouble never delays a stop. */
    Enforcement apply(Asked asked, AuditTrail.Actor actor) {
        var template = template(asked.template())
                .orElseThrow(() -> ApiProblem.invalid("template", "isn't one of the ladder's templates."));
        var now = clock.instant();
        check(asked, template, actor, now);
        var id = ids.newId();
        Objects.requireNonNull(transactions.execute(status -> {
            tell(id, asked, template);
            jdbc.sql("""
                            insert into trust.enforcements (id, user_id, role, step, template_id, limit_kind, limit_hours,
                                ends_at, strike_expires_at, state, reason, enforced_by, created_at)
                            values (:id, :user, :role, :step, :template, :limitKind, :limitHours, :endsAt, :strikeEnds,
                                'active', :reason, :by, :now)
                            """)
                    .param("id", id)
                    .param("user", asked.userId())
                    .param("role", template.role())
                    .param("step", template.step())
                    .param("template", asked.template())
                    .param("limitKind", asked.limitKind())
                    .param("limitHours", asked.limitHours())
                    .param("endsAt", asked.endsAt() == null ? null : Timestamp.from(asked.endsAt()))
                    .param("strikeEnds", Timestamp.from(now.plus(strikeLasts)))
                    .param("reason", asked.reason())
                    .param("by", actor.userId())
                    .param("now", Timestamp.from(now))
                    .update();
            switch (template.step()) {
                case "suspend" -> holds.hold(asked.userId(), asked.reason(), actor);
                case "ban" -> holds.ban(asked.userId(), asked.reason(), actor);
                default -> {
                    // A warning or a limit leaves the account active.
                }
            }
            audit.record(new AuditTrail.Entry(
                    actor,
                    "enforcement." + template.step(),
                    "user",
                    asked.userId().toString(),
                    asked.reason(),
                    null,
                    Map.of("enforcement", id, "template", asked.template())));
            return id;
        }));
        return find(id).orElseThrow();
    }

    private void check(Asked asked, Template template, AuditTrail.Actor actor, Instant now) {
        if (asked.userId().equals(actor.userId())) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "An admin can't enforce against their own account.");
        }
        if (template.step().equals("suspend")
                && (asked.endsAt() == null || !asked.endsAt().isAfter(now))) {
            throw ApiProblem.invalid("endsAt", "A suspension has an end date, after now.");
        }
        if (template.step().equals("limit")) {
            if (asked.limitKind() == null || !LIMITS.contains(asked.limitKind())) {
                throw ApiProblem.invalid("limitKind", "must be no-custom-images, session-hours-cap or payouts-held.");
            }
            if (asked.limitKind().equals("session-hours-cap") && asked.limitHours() == null) {
                throw ApiProblem.invalid("limitHours", "A cap says how many hours a week.");
            }
            if (asked.limitKind().equals("payouts-held") && !template.role().equals("host")) {
                throw ApiProblem.invalid("limitKind", "Payouts are held only for misconduct as a host.");
            }
        } else if (asked.limitKind() != null) {
            throw ApiProblem.invalid("limitKind", "Only a limit has one.");
        }
    }

    /** Announced in the step's transaction, and sent from there: a mail server's trouble never delays a stop. */
    private void tell(UUID id, Asked asked, Template template) {
        var until = asked.endsAt() == null
                ? ""
                : "\n\nThis lasts until "
                        + DateTimeFormatter.ofPattern("d MMMM yyyy")
                                .withZone(INDIA)
                                .format(asked.endsAt()) + ".";
        events.publishEvent(new AdminNoticed(
                asked.userId(),
                "enforcement:" + id,
                template.title(),
                template.body() + until + "\n\nWhy: " + asked.reason() + "\n\nThis is under " + template.clause()
                        + ". If you think we got this wrong, you can appeal, and someone else will look at it.",
                appealLink(id)));
    }

    static String appealLink(UUID id) {
        return "/account/appeal/?id=" + id;
    }

    /** Lifts it, as another admin approved: the account goes active again if this is what held it. */
    void lift(UUID id, String reason, AuditTrail.Actor actor) {
        transactions.executeWithoutResult(status -> {
            var found = find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such step."));
            if (!found.state().equals("active")) {
                throw ApiProblem.of(Problem.ACTION_REFUSED, "This has ended already.");
            }
            end(found, "lifted", reason, actor);
            audit.record(new AuditTrail.Entry(
                    actor,
                    "enforcement.lift",
                    "user",
                    found.userId().toString(),
                    reason,
                    Map.of("enforcement", id),
                    Map.of("state", "lifted")));
        });
    }

    private void end(Enforcement found, String state, String reason, AuditTrail.Actor actor) {
        jdbc.sql("update trust.enforcements set state = :state, ended_at = :now where id = :id")
                .param("state", state)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", found.id())
                .update();
        if ((found.step().equals("suspend") || found.step().equals("ban")) && !otherHold(found)) {
            holds.lift(found.userId(), reason, actor);
        }
    }

    /** Whether another step, still running, keeps the account held. */
    private boolean otherHold(Enforcement found) {
        return jdbc.sql("""
                        select count(*) from trust.enforcements
                        where user_id = :user and id <> :id and state = 'active' and step in ('suspend', 'ban')
                        """)
                        .param("user", found.userId())
                        .param("id", found.id())
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Ends what's reached its end date: its end was part of the decision, so nothing needs approving. */
    @Scheduled(fixedDelayString = "${trust.enforcement.sweep-every}")
    void scheduled() {
        oneAtATime.run("trust:enforcement-ends", this::sweep);
    }

    void sweep() {
        var due = jdbc.sql(ONE + " where e.state = 'active' and e.ends_at <= :now")
                .param("now", Timestamp.from(clock.instant()))
                .query(Enforcements::row)
                .list();
        for (var found : due) {
            transactions.executeWithoutResult(status -> {
                var actor = AuditTrail.Actor.system("the enforcement ladder");
                end(found, "ended", "its end date came", actor);
                audit.record(new AuditTrail.Entry(
                        actor,
                        "enforcement.end",
                        "user",
                        found.userId().toString(),
                        "its end date came",
                        Map.of("enforcement", found.id()),
                        Map.of("state", "ended")));
                events.publishEvent(new AdminNoticed(
                        found.userId(),
                        "enforcement-ended:" + found.id(),
                        "Your " + (found.step().equals("suspend") ? "suspension" : "limit") + " has ended",
                        "It reached its end date, and your account is back as it was.",
                        "/account/"));
            });
        }
    }

    private boolean active(UUID user, String limit) {
        return activeHours(user, limit).isPresent();
    }

    private Optional<Integer> activeHours(UUID user, String limit) {
        return jdbc.sql("""
                        select coalesce(min(limit_hours), 0) from trust.enforcements
                        where user_id = :user and state = 'active' and step = 'limit' and limit_kind = :limit
                        having count(*) > 0
                        """)
                .param("user", user)
                .param("limit", limit)
                .query(Integer.class)
                .optional();
    }

    @Override
    public boolean noCustomImages(UUID user) {
        return active(user, "no-custom-images");
    }

    @Override
    public boolean payoutsHeld(UUID user) {
        return active(user, "payouts-held");
    }

    @Override
    public OptionalInt sessionHoursCap(UUID user) {
        return activeHours(user, "session-hours-cap").map(OptionalInt::of).orElse(OptionalInt.empty());
    }

    @Override
    public boolean warning(String id) {
        return template(id).filter(found -> found.step().equals("warn")).isPresent();
    }

    @Override
    public void warn(UUID person, String template, String reason, UUID admin) {
        if (!warning(template)) {
            throw ApiProblem.invalid("template", "A bulk action can only warn.");
        }
        apply(
                new Asked(person, template, null, null, null, reason),
                new AuditTrail.Actor(AuditTrail.Actor.Kind.ADMIN, admin, admin.toString()));
    }
}
