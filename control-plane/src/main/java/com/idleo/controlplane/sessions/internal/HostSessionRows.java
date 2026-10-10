package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.billing.HostEarnings;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The sessions a host ran, one row per attempt that ran on it, newest first (ADR 0023). A host sees the service, the
 * time and the money, never the renter, the session's inputs or its parameters.
 */
@Component
class HostSessionRows {

    /** What happened to the attempt on this host. */
    enum Outcome {
        RUNNING,
        COMPLETED,
        STOPPED,
        FAILED,
        LOST
    }

    record Row(
            UUID sessionId,
            int attempt,
            String templateId,
            Instant startedAt,
            @Nullable Instant endedAt,
            int minutes,
            Outcome outcome,
            HostEarnings.Earned earned) {}

    record Page(List<Row> rows, @Nullable String nextCursor) {}

    private record Raw(
            UUID sessionId,
            int attempt,
            String templateId,
            Instant startedAt,
            @Nullable Instant endedAt,
            int latestAttempt,
            String state,
            boolean stopped) {}

    private final JdbcClient jdbc;
    private final HostEarnings earnings;
    private final Clock clock;

    HostSessionRows(JdbcClient jdbc, HostEarnings earnings, Clock clock) {
        this.jdbc = jdbc;
        this.earnings = earnings;
        this.clock = clock;
    }

    Page page(UUID hostId, @Nullable String cursor, int limit) {
        var after = cursor == null ? null : Cursors.hostAttempt(cursor);
        var statement = jdbc.sql("""
                        select a.session_id, a.attempt, s.template_id, a.running_at, a.ended_at,
                               s.attempt as latest_attempt, s.state,
                               exists (select 1 from sessions.session_events e
                                       where e.session_id = s.id and e.to_state = 'COMPLETED'
                                         and e.reason = 'stopped') as stopped
                        from sessions.attempts a
                        join sessions.sessions s on s.id = a.session_id
                        where a.host_id = :hostId and a.running_at is not null
                        """
                        + (after == null
                                ? ""
                                : "and (a.running_at, a.session_id, a.attempt) < (:at, :sessionId, :attempt)\n")
                        + "order by a.running_at desc, a.session_id desc, a.attempt desc limit :limit")
                .param("hostId", hostId)
                .param("limit", limit + 1);
        if (after != null) {
            statement = statement
                    .param("at", Timestamp.from(after.startedAt()))
                    .param("sessionId", after.sessionId())
                    .param("attempt", after.attempt());
        }
        var raw = statement
                .query((rs, n) -> new Raw(
                        rs.getObject("session_id", UUID.class),
                        rs.getInt("attempt"),
                        rs.getString("template_id"),
                        rs.getTimestamp("running_at").toInstant(),
                        rs.getTimestamp("ended_at") == null
                                ? null
                                : rs.getTimestamp("ended_at").toInstant(),
                        rs.getInt("latest_attempt"),
                        rs.getString("state"),
                        rs.getBoolean("stopped")))
                .list();
        var shown = raw.size() > limit ? raw.subList(0, limit) : raw;
        var earned = earnings.bySession(
                hostId, shown.stream().map(Raw::sessionId).distinct().toList());
        var now = Instant.now(clock);
        var rows = shown.stream()
                .map(r -> new Row(
                        r.sessionId(),
                        r.attempt(),
                        r.templateId(),
                        r.startedAt(),
                        r.endedAt(),
                        minutes(r.startedAt(), r.endedAt() == null ? now : r.endedAt()),
                        outcome(r),
                        earnedOn(earned, r, shown)))
                .toList();
        var next = raw.size() > limit
                ? Cursors.ofHostAttempt(
                        shown.getLast().startedAt(),
                        shown.getLast().sessionId(),
                        shown.getLast().attempt())
                : null;
        return new Page(rows, next);
    }

    /**
     * What the session paid the host is on the ledger by session, not by attempt, so a host that ran two attempts of a
     * session shows it against the newest one only.
     */
    private static HostEarnings.Earned earnedOn(
            java.util.Map<UUID, HostEarnings.Earned> earned, Raw row, List<Raw> shown) {
        boolean newestOnThisHost = shown.stream()
                .filter(other -> other.sessionId().equals(row.sessionId()))
                .allMatch(other -> other.attempt() <= row.attempt());
        return newestOnThisHost
                ? earned.getOrDefault(row.sessionId(), HostEarnings.Earned.NONE)
                : HostEarnings.Earned.NONE;
    }

    /** A started minute counts whole, as it's charged (ADR 0017). */
    static int minutes(Instant from, Instant until) {
        long seconds = Math.max(0, Duration.between(from, until).getSeconds());
        return (int) ((seconds + 59) / 60);
    }

    private static Outcome outcome(Raw row) {
        if (row.attempt() < row.latestAttempt()
                || row.state().equals("HOST_LOST")
                || row.state().equals("ENDED_REFUNDED")) {
            return Outcome.LOST;
        }
        return switch (row.state()) {
            case "COMPLETED" -> row.stopped() ? Outcome.STOPPED : Outcome.COMPLETED;
            case "FAILED" -> Outcome.FAILED;
            default -> Outcome.RUNNING;
        };
    }
}
