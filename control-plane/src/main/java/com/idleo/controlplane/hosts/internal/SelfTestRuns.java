package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostAgentReports.SelfTestCheck;
import com.idleo.controlplane.hosts.HostPresence;
import com.idleo.controlplane.hosts.HostWork;
import com.idleo.controlplane.hosts.SelfTestRequested;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Run self-test" on a machine's page (ADR 0034): the host's owner, or an admin, asks and the agent runs its full
 * self-test over its stream, each check showing as it arrives. The rules live here, once, for both pages:
 *
 * <ul>
 *   <li>one run at a time (a run that hasn't finished {@link #RUN_LASTS} after it was asked for has been lost, and
 *       stops counting);
 *   <li>not while the machine is offline, or runs a session;
 *   <li>at most {@link #PER_DAY} runs a host in 24 hours, those the agent refused included.
 * </ul>
 *
 * An admin pressing it is audited, with the reason they gave.
 */
@Service
class SelfTestRuns {

    static final int PER_DAY = 6;
    static final Duration RUN_LASTS = Duration.ofMinutes(10);
    private static final TypeReference<List<SelfTestCheck>> CHECKS = new TypeReference<>() {};

    /** A run as the page shows it. {@code state}: running, passed, failed, refused or timed_out. */
    record SelfTestRunView(
            UUID id,
            String state,
            Instant requestedAt,
            boolean byAdmin,
            List<SelfTestCheck> checks,
            @Nullable String refused,
            @Nullable Instant finishedAt) {}

    /** Why a run can't be started now, as the problem it would answer with and the words for the person. */
    record Blocked(Problem problem, String message) {

        ApiProblem toProblem() {
            return ApiProblem.of(problem, message);
        }
    }

    /** Why the button is off, in the problem's slug and in words. */
    record SelfTestWhyNot(String code, String message) {}

    /** What a machine's page needs: the newest run, and whether another can start now. */
    record SelfTestStatus(
            @Nullable SelfTestRunView run,
            boolean canRun,
            @Nullable SelfTestWhyNot whyNot,
            int usedToday,
            int perDay,
            @Nullable Instant retryAt) {}

    /** Who asked. */
    record Requester(
            boolean admin,
            UUID userId,
            AuditTrail.@Nullable Actor actor,
            @Nullable String reason) {

        static Requester owner(UUID userId) {
            return new Requester(false, userId, null, null);
        }

        static Requester admin(UUID userId, AuditTrail.Actor actor, String reason) {
            return new Requester(true, userId, actor, reason);
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final IdGenerator ids;
    private final HostPresence presence;
    private final HostWork work;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final JsonMapper json = JsonMapper.builder().build();

    SelfTestRuns(
            JdbcClient jdbc,
            Clock clock,
            IdGenerator ids,
            HostPresence presence,
            HostWork work,
            AuditTrail audit,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ids = ids;
        this.presence = presence;
        this.work = work;
        this.audit = audit;
        this.events = events;
    }

    /** The host's newest run, if it has had one. */
    Optional<SelfTestRunView> latest(UUID hostId) {
        return jdbc.sql("""
                        select * from hosts.self_test_runs where host_id = :hostId
                        order by requested_at desc, id desc limit 1
                        """).param("hostId", hostId).query((rs, n) -> run(rs)).optional();
    }

    /** How many runs the host has had in the last 24 hours. */
    int usedToday(UUID hostId) {
        return jdbc.sql("""
                        select count(*) from hosts.self_test_runs
                        where host_id = :hostId and requested_at > :since
                        """)
                .param("hostId", hostId)
                .param("since", Timestamp.from(Instant.now(clock).minus(Duration.ofDays(1))))
                .query(Integer.class)
                .single();
    }

    /** When the oldest of the day's runs stops counting, once the day's limit is reached. */
    Optional<Instant> limitLiftsAt(UUID hostId) {
        return jdbc.sql("""
                        select requested_at from hosts.self_test_runs
                        where host_id = :hostId and requested_at > :since
                        order by requested_at desc offset :keep limit 1
                        """)
                .param("hostId", hostId)
                .param("since", Timestamp.from(Instant.now(clock).minus(Duration.ofDays(1))))
                .param("keep", PER_DAY - 1)
                .query((rs, n) -> rs.getTimestamp("requested_at").toInstant().plus(Duration.ofDays(1)))
                .optional();
    }

    SelfTestStatus status(UUID hostId) {
        var blocked = blocked(hostId);
        return new SelfTestStatus(
                latest(hostId).orElse(null),
                blocked.isEmpty(),
                blocked.map(b -> new SelfTestWhyNot(b.problem().slug(), b.message()))
                        .orElse(null),
                usedToday(hostId),
                PER_DAY,
                blocked.filter(b -> b.problem() == Problem.SELF_TEST_LIMIT)
                        .flatMap(b -> limitLiftsAt(hostId))
                        .orElse(null));
    }

    /** Why a run can't start now, or empty if it can. */
    Optional<Blocked> blocked(UUID hostId) {
        var latest = latest(hostId);
        if (latest.isPresent() && latest.get().state().equals("running")) {
            return Optional.of(new Blocked(
                    Problem.SELF_TEST_RUNNING,
                    "A self-test is already running on this machine. Wait for it to finish."));
        }
        if (!presence.isOnline(hostId)) {
            return Optional.of(new Blocked(
                    Problem.HOST_OFFLINE, "This machine isn't connected right now. Open IdleO on it, then try again."));
        }
        if (work.isBusy(hostId)) {
            return Optional.of(new Blocked(
                    Problem.HOST_BUSY,
                    "A renter's session is running on this machine. The self-test can run when it ends."));
        }
        if (usedToday(hostId) >= PER_DAY) {
            return Optional.of(new Blocked(
                    Problem.SELF_TEST_LIMIT,
                    "This machine has run its self-test " + PER_DAY + " times in the last 24 hours. Try again later."));
        }
        return Optional.empty();
    }

    /**
     * Starts a run, or says why not. The host's row is locked while it decides, so two presses at once start one run.
     *
     * @throws ApiProblem {@code host-offline}, {@code host-busy}, {@code self-test-running} or {@code
     *     self-test-limit}
     */
    @Transactional
    SelfTestRunView start(UUID hostId, Requester requester) {
        jdbc.sql("select id from hosts.hosts where id = :hostId for update")
                .param("hostId", hostId)
                .query(UUID.class)
                .single();
        var blocked = blocked(hostId);
        if (blocked.isPresent()) {
            throw blocked.get().toProblem();
        }
        var now = Instant.now(clock);
        var runId = ids.newId();
        jdbc.sql("""
                        insert into hosts.self_test_runs
                            (id, host_id, requested_by_kind, requested_by, requested_at, state)
                        values (:id, :hostId, :kind, :by, :now, 'running')
                        """)
                .param("id", runId)
                .param("hostId", hostId)
                .param("kind", requester.admin() ? "admin" : "owner")
                .param("by", requester.userId())
                .param("now", Timestamp.from(now))
                .update();
        if (requester.admin()) {
            audit.record(new AuditTrail.Entry(
                    requester.actor(),
                    "host.run_self_test",
                    "host",
                    hostId.toString(),
                    requester.reason(),
                    null,
                    Map.of("runId", runId.toString())));
        }
        events.publishEvent(new SelfTestRequested(hostId, runId));
        return latest(hostId).orElseThrow();
    }

    /** A check of the run arrived, or the agent refused it. Ignored for a run that isn't the host's or is over. */
    @Transactional
    void progress(UUID hostId, UUID runId, @Nullable SelfTestCheck check, @Nullable String refused) {
        var row = jdbc.sql("""
                        select * from hosts.self_test_runs
                        where id = :runId and host_id = :hostId and state = 'running' for update
                        """)
                .param("runId", runId)
                .param("hostId", hostId)
                .query((rs, n) -> run(rs))
                .optional();
        if (row.isEmpty()) {
            return;
        }
        if (refused != null) {
            jdbc.sql("""
                            update hosts.self_test_runs set state = 'refused', refused = :refused, finished_at = :now
                            where id = :runId
                            """)
                    .param("refused", refused)
                    .param("now", Timestamp.from(Instant.now(clock)))
                    .param("runId", runId)
                    .update();
            return;
        }
        if (check == null
                || row.get().checks().stream().anyMatch(known -> known.name().equals(check.name()))) {
            return;
        }
        var checks = new java.util.ArrayList<>(row.get().checks());
        checks.add(check);
        jdbc.sql("update hosts.self_test_runs set checks = cast(:checks as jsonb) where id = :runId")
                .param("checks", json.writeValueAsString(checks))
                .param("runId", runId)
                .update();
    }

    /** The agent's report for the run: its checks are the final word, and the run ends. */
    @Transactional
    void finished(UUID hostId, UUID runId, boolean passed, List<SelfTestCheck> checks) {
        jdbc.sql("""
                        update hosts.self_test_runs
                        set state = :state, checks = cast(:checks as jsonb), finished_at = :now
                        where id = :runId and host_id = :hostId and state = 'running'
                        """)
                .param("state", passed ? "passed" : "failed")
                .param("checks", json.writeValueAsString(checks))
                .param("now", Timestamp.from(Instant.now(clock)))
                .param("runId", runId)
                .param("hostId", hostId)
                .update();
    }

    private SelfTestRunView run(java.sql.ResultSet rs) throws java.sql.SQLException {
        var requestedAt = rs.getTimestamp("requested_at").toInstant();
        var state = rs.getString("state");
        if (state.equals("running") && requestedAt.plus(RUN_LASTS).isBefore(Instant.now(clock))) {
            state = "timed_out";
        }
        var finished = rs.getTimestamp("finished_at");
        return new SelfTestRunView(
                rs.getObject("id", UUID.class),
                state,
                requestedAt,
                rs.getString("requested_by_kind").equals("admin"),
                json.readValue(rs.getString("checks"), CHECKS),
                rs.getString("refused"),
                finished == null ? null : finished.toInstant());
    }
}
