package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The setup app's measurements (host experience §6 row 11): each step's start and end with its duration, the clicks,
 * and where an install stopped, so we can see where hosts struggle. Sent without a sign-in under an install id the app
 * makes; at most {@link #MAX_PER_INSTALL} per install. Never the Windows user name, a path or the machine's serial:
 * the fields are fixed and short. An install is tied to its host through its link ({@code hosts.host_links.install_id})
 * once that's approved, and the approval itself is recorded here as the last click.
 */
@Service
class HostInstalls {

    static final int MAX_PER_REQUEST = 20;
    static final int MAX_PER_INSTALL = 200;
    /** The first event of every install, and the one that means the laptop is listed. */
    static final String OPENED = "setup_opened";

    static final String LISTED = "listed";
    /** A click: {@code reason} says which button. The approval in the browser is one, recorded by the server. */
    static final String CLICK = "click";

    static final String APPROVE = "approve";

    /** One event, as the app sends it. */
    record Event(
            String step,
            String outcome,
            @Nullable String reason,
            @Nullable Long durationMs,
            Instant at,
            @Nullable String setupVersion,
            @Nullable Integer windowsBuild,
            @Nullable String gpuModel) {}

    /** One install, for the admin console. */
    record Install(
            UUID installId,
            Instant openedAt,
            @Nullable Double minutes,
            int clicks,
            String stoppedAt,
            @Nullable String setupVersion,
            @Nullable Integer windowsBuild,
            @Nullable String gpuModel,
            @Nullable UUID hostId) {}

    /** How many failures a step had, for one reason. */
    record Failures(String step, String reason, int count) {}

    /**
     * The admin console's Installs card: the median minutes from {@code setup_opened} to {@code listed} over installs
     * opened in the last 30 days that got listed, how many opened, the last 20, and the failures by reason.
     */
    record Summary(
            @Nullable Double medianMinutes, int opened, int listed, List<Install> recent, List<Failures> failures) {}

    static final Duration WINDOW = Duration.ofDays(30);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;

    HostInstalls(JdbcClient jdbc, PlatformTransactionManager transactionManager, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Keeps a batch of events, all or none. Refused with {@code install-events-full} if the install would pass
     * {@link #MAX_PER_INSTALL}; batches for one install take turns, so the count can't be raced past.
     */
    void record(UUID installId, List<Event> events) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", "install-events:" + installId)
                    .query()
                    .listOfRows();
            long kept = jdbc.sql("select count(*) from hosts.install_events where install_id = :installId")
                    .param("installId", installId)
                    .query(Long.class)
                    .single();
            if (kept + events.size() > MAX_PER_INSTALL) {
                throw ApiProblem.of(
                        Problem.INSTALL_EVENTS_FULL,
                        "This install already has " + kept + " events; at most " + MAX_PER_INSTALL + " are kept.");
            }
            var now = Timestamp.from(Instant.now(clock));
            for (var event : events) {
                insert(installId, event, now);
            }
        });
    }

    /** The owner approved a link that came from an install: that's the install's last click. */
    void approved(UUID installId) {
        var now = Instant.now(clock);
        insert(installId, new Event(CLICK, "passed", APPROVE, null, now, null, null, null), Timestamp.from(now));
    }

    private void insert(UUID installId, Event event, Timestamp now) {
        jdbc.sql("""
                        insert into hosts.install_events
                            (id, install_id, step, outcome, reason, duration_ms, at, setup_version, windows_build,
                             gpu_model, received_at)
                        values (:id, :installId, :step, :outcome, :reason, :durationMs, :at, :setupVersion,
                                :windowsBuild, :gpuModel, :now)
                        """)
                .param("id", ids.newId())
                .param("installId", installId)
                .param("step", event.step())
                .param("outcome", event.outcome())
                .param("reason", event.reason())
                .param("durationMs", event.durationMs())
                .param("at", Timestamp.from(event.at()))
                .param("setupVersion", event.setupVersion())
                .param("windowsBuild", event.windowsBuild())
                .param("gpuModel", event.gpuModel())
                .param("now", now)
                .update();
    }

    Summary summary() {
        var since = Timestamp.from(Instant.now(clock).minus(WINDOW));
        record Row(
                UUID installId,
                Instant openedAt,
                @Nullable Instant listedAt,
                int clicks,
                @Nullable String lastStep,
                @Nullable String lastOutcome,
                @Nullable String lastReason,
                @Nullable String setupVersion,
                @Nullable Integer windowsBuild,
                @Nullable String gpuModel,
                @Nullable UUID hostId) {}
        var rows = jdbc.sql("""
                        with opened as (
                            select install_id, min(at) as opened_at from hosts.install_events
                            where step = :opened group by install_id having min(at) >= :since
                        )
                        select o.install_id, o.opened_at,
                               (select min(at) from hosts.install_events e
                                where e.install_id = o.install_id and e.step = :listed) as listed_at,
                               (select count(*) from hosts.install_events e
                                where e.install_id = o.install_id and e.step = :click) as clicks,
                               last.step as last_step, last.outcome as last_outcome, last.reason as last_reason,
                               (select max(setup_version) from hosts.install_events e
                                where e.install_id = o.install_id) as setup_version,
                               (select max(windows_build) from hosts.install_events e
                                where e.install_id = o.install_id) as windows_build,
                               (select max(gpu_model) from hosts.install_events e
                                where e.install_id = o.install_id) as gpu_model,
                               (select l.host_id from hosts.host_links l
                                where l.install_id = o.install_id and l.host_id is not null
                                order by l.approved_at desc limit 1) as host_id
                        from opened o
                        left join lateral (
                            select step, outcome, reason from hosts.install_events e
                            where e.install_id = o.install_id and e.step <> :click
                            order by e.at desc, e.received_at desc limit 1
                        ) last on true
                        order by o.opened_at desc
                        """)
                .param("opened", OPENED)
                .param("listed", LISTED)
                .param("click", CLICK)
                .param("since", since)
                .query((rs, n) -> new Row(
                        rs.getObject("install_id", UUID.class),
                        rs.getTimestamp("opened_at").toInstant(),
                        rs.getTimestamp("listed_at") == null
                                ? null
                                : rs.getTimestamp("listed_at").toInstant(),
                        rs.getInt("clicks"),
                        rs.getString("last_step"),
                        rs.getString("last_outcome"),
                        rs.getString("last_reason"),
                        rs.getString("setup_version"),
                        (Integer) rs.getObject("windows_build"),
                        rs.getString("gpu_model"),
                        rs.getObject("host_id", UUID.class)))
                .list();
        var minutes = new ArrayList<Double>();
        var recent = new ArrayList<Install>();
        for (var row : rows) {
            Double took = row.listedAt() == null
                    ? null
                    : Duration.between(row.openedAt(), row.listedAt()).toMillis() / 60_000.0;
            if (took != null) {
                minutes.add(took);
            }
            if (recent.size() < 20) {
                recent.add(new Install(
                        row.installId(),
                        row.openedAt(),
                        took,
                        row.clicks(),
                        stoppedAt(row.listedAt() != null, row.lastStep(), row.lastOutcome(), row.lastReason()),
                        row.setupVersion(),
                        row.windowsBuild(),
                        row.gpuModel(),
                        row.hostId()));
            }
        }
        var failures = jdbc.sql("""
                        select step, coalesce(reason, 'unknown') as reason, count(*) as count
                        from hosts.install_events where outcome = 'failed' and received_at >= :since
                        group by step, coalesce(reason, 'unknown') order by count desc, step, reason
                        """)
                .param("since", since)
                .query((rs, n) -> new Failures(rs.getString("step"), rs.getString("reason"), rs.getInt("count")))
                .list();
        return new Summary(median(minutes), rows.size(), minutes.size(), recent, failures);
    }

    /** {@code listed}, or the last step and how it went: {@code drivers failed: driver_too_old}. */
    static String stoppedAt(boolean listed, @Nullable String step, @Nullable String outcome, @Nullable String reason) {
        if (listed) {
            return LISTED;
        }
        var where = Objects.requireNonNullElse(step, OPENED);
        if ("failed".equals(outcome)) {
            return where + " failed" + (reason == null ? "" : ": " + reason);
        }
        return where;
    }

    static @Nullable Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        var sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
}
