package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminListing;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.AdminTagged;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import com.idleo.controlplane.identity.PersonalDataAccess;
import com.idleo.controlplane.sessions.SessionCosts;
import jakarta.validation.Valid;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sessions, for the admin console (ADR 0027): find them, read one's timeline and attempts, and force-stop a running
 * one. An admin sees what a session is and how it went, never its parameters or its inputs.
 */
@RestController
@RequestMapping("/admin/v1/sessions")
@ConditionalOnWebApplication
class AdminSessionsController {

    /** The list, its filters, orders and CSV (ADR 0052). */
    static final AdminListing LIST = AdminListing.of("sessions", AdminPermission.SESSIONS_READ, false)
            .select("""
                    id, version, renter_id, host_id, template_id, state, attempt, price_paise_per_min,
                    max_price_paise_per_min, max_spend_paise, created_at, started_at, state_changed_at, error_code""")
            .from("sessions.sessions")
            .sort("createdAt", AdminListing.Type.UUID, "id")
            .sort("state", AdminListing.Type.TEXT, "state")
            .sort("stateChangedAt", AdminListing.Type.TIME, "state_changed_at")
            .sortedBy("createdAt", true)
            .filter("id", "id = cast(:id as uuid)")
            .filter("user", "renter_id = cast(:user as uuid)")
            .filter("host", """
                    host_id = cast(:host as uuid)
                    or exists (select 1 from sessions.attempts a
                               where a.session_id = sessions.id and a.host_id = cast(:host as uuid))""")
            .filter("state", "state = cast(:state as text)")
            .filter("from", "created_at >= cast(:from as timestamptz)")
            .filter("to", "created_at < cast(:to as timestamptz)")
            .filter("tagged", "id in (:tagged)")
            .filter("demandClass", SessionsControlRoom.CLASS_ASKED + " = cast(:demandClass as integer)")
            .filter("demandHour", SessionsControlRoom.HOUR_ASKED + " = cast(:demandHour as integer)")
            .filter("served", "(started_at is not null) = cast(:served as boolean)")
            .csv("id", "id")
            .csv("renterId", "renter_id")
            .csv("hostId", "host_id")
            .csv("templateId", "template_id")
            .csv("state", "state")
            .csv("pricePaisePerMin", "price_paise_per_min")
            .csv("createdAt", "created_at")
            .csv("stateChangedAt", "state_changed_at")
            .build();

    private static final String SELECT = """
            select id, version, renter_id, host_id, template_id, state, attempt, price_paise_per_min, max_price_paise_per_min,
                   max_spend_paise, created_at, started_at, state_changed_at, error_code
            from sessions.sessions
            """;

    private final JdbcClient jdbc;
    private final SessionForceStops forceStops;
    private final SessionCosts costs;
    private final AuditTrail audit;
    private final SessionLogReviews logs;
    private final PersonalDataAccess accessLog;
    private final AdminTagged tagged;

    AdminSessionsController(
            JdbcClient jdbc,
            SessionForceStops forceStops,
            SessionCosts costs,
            AuditTrail audit,
            SessionLogReviews logs,
            PersonalDataAccess accessLog,
            AdminTagged tagged) {
        this.accessLog = accessLog;
        this.tagged = tagged;
        this.logs = logs;
        this.jdbc = jdbc;
        this.forceStops = forceStops;
        this.costs = costs;
        this.audit = audit;
    }

    /** A session in a list: whose, on which host, what it runs, where it is, and its price. */
    record AdminSessionSummary(
            UUID id,
            UUID renterId,
            @Nullable UUID hostId,
            String templateId,
            String state,
            int attempt,
            @Nullable Integer pricePaisePerMin,
            int maxPricePaisePerMin,
            long maxSpendPaise,
            Instant createdAt,
            @Nullable Instant startedAt,
            Instant stateChangedAt,
            @Nullable String errorCode,
            long version) {}

    record AdminSessionPage(
            List<AdminSessionSummary> sessions, @Nullable String nextCursor) {}

    /** One change of state, from the append-only timeline. */
    record AdminSessionEvent(@Nullable String from, String to, String reason, Instant at) {}

    /** One attempt: the host and GPU it ran on, its price, when it ran, and how it was stopped. */
    record AdminSessionAttempt(
            int attempt,
            UUID hostId,
            List<String> gpuUuids,
            int pricePaisePerMin,
            Instant createdAt,
            @Nullable Instant runningAt,
            @Nullable Instant endedAt,
            @Nullable Instant stopRequestedAt,
            @Nullable String stopReason,
            int latestLogSeq) {}

    /** A session's timeline, its attempts, and what it has been charged so far. */
    record AdminSession(
            AdminSessionSummary session,
            List<AdminSessionEvent> timeline,
            List<AdminSessionAttempt> attempts,
            long chargedPaise,
            long accruedPaise,
            boolean settled) {}

    /**
     * Filters: {@code id} a session, {@code user} its renter, {@code host} a host it ran on, {@code state} its state
     * ({@code RUNNING}), and {@code from} and {@code to} when it was booked.
     */
    @RequiresPermission(AdminPermission.SESSIONS_READ)
    @AdminList("sessions")
    @GetMapping
    AdminSessionPage search(
            @RequestParam(required = false) @Nullable UUID id,
            @RequestParam(required = false) @Nullable UUID user,
            @RequestParam(required = false) @Nullable UUID host,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(required = false) @Nullable Integer demandClass,
            @RequestParam(required = false) @Nullable Integer demandHour,
            @RequestParam(required = false) @Nullable Boolean served,
            @RequestParam(required = false) @Nullable String tag,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        var page = LIST.page(
                jdbc,
                AdminListing.Asked.of(
                        sort,
                        "id",
                        id,
                        "user",
                        user,
                        "host",
                        host,
                        "state",
                        state == null || state.isBlank() ? null : state.strip().toUpperCase(Locale.ROOT),
                        "from",
                        from == null ? null : Timestamp.from(from),
                        "to",
                        to == null ? null : Timestamp.from(to),
                        "demandClass",
                        demandClass,
                        "demandHour",
                        demandHour,
                        "served",
                        served,
                        "tagged",
                        tagged.subjects("session", tag)),
                cursor,
                limit,
                AdminSessionsController::summary);
        return new AdminSessionPage(page.rows(), page.nextCursor());
    }

    @RequiresPermission(AdminPermission.SESSIONS_READ)
    @GetMapping("/{id}")
    AdminSession get(@AuthenticationPrincipal CurrentUser admin, @PathVariable UUID id) {
        var session = find(id);
        accessLog.viewed(admin, session.session().renterId(), id.toString());
        return session;
    }

    private AdminSession find(UUID id) {
        var summary = jdbc.sql(SELECT + " where id = :id")
                .param("id", id)
                .query(AdminSessionsController::summary)
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such session."));
        var timeline = jdbc.sql("""
                        select from_state, to_state, reason, at from sessions.session_events
                        where session_id = :id order by at, id
                        """)
                .param("id", id)
                .query((rs, n) -> new AdminSessionEvent(
                        rs.getString("from_state"),
                        rs.getString("to_state"),
                        rs.getString("reason"),
                        rs.getTimestamp("at").toInstant()))
                .list();
        var attempts = jdbc.sql("select * from sessions.attempts where session_id = :id order by attempt")
                .param("id", id)
                .query((rs, n) -> new AdminSessionAttempt(
                        rs.getInt("attempt"),
                        rs.getObject("host_id", UUID.class),
                        List.of((String[]) rs.getArray("gpu_uuids").getArray()),
                        rs.getInt("price_paise_per_min"),
                        rs.getTimestamp("created_at").toInstant(),
                        instant(rs.getTimestamp("running_at")),
                        instant(rs.getTimestamp("ended_at")),
                        instant(rs.getTimestamp("stop_requested_at")),
                        rs.getString("stop_reason"),
                        rs.getInt("latest_log_seq")))
                .list();
        var cost = costs.of(id);
        return new AdminSession(summary, timeline, attempts, cost.chargedPaise(), cost.accruedPaise(), cost.closed());
    }

    /** A page of a session's log. */
    record AdminSessionLogChunk(int attempt, int seq, String text) {}

    record AdminSessionLog(
            List<AdminSessionLogChunk> chunks, @Nullable String nextCursor) {}

    /**
     * A session's log (ADR 0039), to judge a report or gather a chargeback's evidence: {@code reason} (5 to 500
     * characters) is why, and the first page is an audit row with it; {@code nextCursor} is null once there's nothing
     * more now.
     */
    @RequiresPermission(AdminPermission.SESSIONS_LOG_READ)
    @GetMapping("/{id}/log")
    AdminSessionLog log(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @RequestParam String reason,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "200") int limit) {
        var why = reason.strip();
        if (why.length() < 5 || why.length() > 500) {
            throw ApiProblem.invalid("reason", "must be 5 to 500 characters.");
        }
        var page = logs.read(id, cursor, Cursors.limit(limit, 1000), why, audit.admin(admin))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no session " + id + "."));
        return new AdminSessionLog(
                page.chunks().stream()
                        .map(chunk -> new AdminSessionLogChunk(chunk.attempt(), chunk.seq(), chunk.text()))
                        .toList(),
                page.more() ? SessionLogs.cursorAfter(page, cursor) : null);
    }

    /**
     * Stops a running session at once, without a checkpoint. It then settles like any stopped session: the minutes it
     * ran are charged. 409 {@code session-not-running} for a session that isn't running.
     */
    @RequiresPermission(value = AdminPermission.SESSIONS_STOP, record = "session")
    @PostMapping("/{id}/stop")
    AdminSession stop(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        switch (forceStops.stop(id, request.reason(), audit.admin(admin))) {
            case NO_SUCH_SESSION -> throw ApiProblem.of(Problem.NOT_FOUND, "There's no such session.");
            case NOT_RUNNING ->
                throw ApiProblem.of(Problem.SESSION_NOT_RUNNING, "Only a running session can be stopped.");
            case STOPPING, ALREADY_STOPPING -> {
                // Shown below as it is now.
            }
        }
        return find(id);
    }

    private static AdminSessionSummary summary(ResultSet rs, int row) throws SQLException {
        return new AdminSessionSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("renter_id", UUID.class),
                rs.getObject("host_id", UUID.class),
                rs.getString("template_id"),
                rs.getString("state"),
                rs.getInt("attempt"),
                rs.getObject("price_paise_per_min", Integer.class),
                rs.getInt("max_price_paise_per_min"),
                rs.getLong("max_spend_paise"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("started_at")),
                rs.getTimestamp("state_changed_at").toInstant(),
                rs.getString("error_code"),
                rs.getLong("version"));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
