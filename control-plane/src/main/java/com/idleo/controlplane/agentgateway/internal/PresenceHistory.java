package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.IdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Each host's presence over time (ADR 0056): only its changes, online, offline or unknown, with when, never an address.
 * A new stream is always an online row, so a reconnect counts even when the new stream replaced the old before the old
 * one closed. Any other change is written only when it is one, and only if nothing newer has been recorded, so a late
 * observation (the sweep's lookup taken just before a stream opened) never writes over a newer state. A write that
 * fails is logged and dropped: presence history never gets in an agent's way.
 */
@Component
class PresenceHistory {

    private static final Logger log = LoggerFactory.getLogger(PresenceHistory.class);

    enum State {
        ONLINE,
        OFFLINE,
        UNKNOWN;

        String db() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static State of(String db) {
            return valueOf(db.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** One change. */
    record Change(UUID hostId, State state, Instant at) {}

    private final JdbcClient jdbc;
    private final IdGenerator ids;

    PresenceHistory(JdbcClient jdbc, IdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    /** A new stream opened for the host at {@code at}. */
    void opened(UUID hostId, Instant at) {
        write(hostId, State.ONLINE, at, true);
    }

    /** The host's stream closed, or its record lapsed, as seen at {@code at}. */
    void offline(UUID hostId, Instant at) {
        write(hostId, State.OFFLINE, at, false);
    }

    /** Presence couldn't be read at {@code at}, so whether the host is online isn't known. */
    void unknown(UUID hostId, Instant at) {
        write(hostId, State.UNKNOWN, at, false);
    }

    /** Presence could be read again at {@code at} and the host is online, without a new stream. */
    void seenOnline(UUID hostId, Instant at) {
        write(hostId, State.ONLINE, at, false);
    }

    private void write(UUID hostId, State state, Instant at, boolean always) {
        try {
            jdbc.sql("""
                            with changed as (
                                insert into agentgateway.presence_states (host_id, state, since)
                                values (:host, :state, :at)
                                on conflict (host_id) do update set state = excluded.state, since = excluded.since
                                where presence_states.since <= excluded.since
                                  and (cast(:always as boolean) or presence_states.state <> excluded.state)
                                returning host_id
                            )
                            insert into agentgateway.presence_changes (id, host_id, state, at)
                            select :id, host_id, :state, :at from changed
                            """)
                    .param("host", hostId)
                    .param("state", state.db())
                    .param("at", Timestamp.from(at))
                    .param("always", always)
                    .param("id", ids.newId())
                    .update();
        } catch (RuntimeException e) {
            log.warn("Couldn't record host {} going {}: {}", hostId, state.db(), e.toString());
        }
    }

    /** The hosts last recorded online or unknown, for the sweep to look at. */
    List<Recorded> notOffline() {
        return jdbc.sql("select host_id, state, since from agentgateway.presence_states where state <> 'offline'")
                .query((rs, n) -> new Recorded(
                        rs.getObject("host_id", UUID.class),
                        State.of(rs.getString("state")),
                        rs.getTimestamp("since").toInstant()))
                .list();
    }

    record Recorded(UUID hostId, State state, Instant since) {}

    /** The host's changes from {@code from}, newest first, at most {@code limit}. */
    List<Change> changes(UUID hostId, Instant from, int limit) {
        return jdbc.sql("""
                        select host_id, state, at from agentgateway.presence_changes
                        where host_id = :host and at >= :from order by at desc, id desc limit :limit
                        """)
                .param("host", hostId)
                .param("from", Timestamp.from(from))
                .param("limit", limit)
                .query((rs, n) -> new Change(
                        rs.getObject("host_id", UUID.class),
                        State.of(rs.getString("state")),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    /** One stretch a host was online. */
    record Interval(UUID hostId, Instant from, Instant to) {}

    /**
     * Every stretch of every host online that overlaps {@code from} to {@code to}, cut to it: from each online change to
     * the next change of the host, or to {@code to}. An unknown stretch counts as neither.
     */
    List<Interval> online(Instant from, Instant to) {
        var intervals = new ArrayList<Interval>();
        jdbc.sql("""
                        with seen as (
                            select host_id, state, at,
                                   lead(at) over (partition by host_id order by at, id) as until
                            from agentgateway.presence_changes
                            where at < :to and at >= :lookback
                        )
                        select host_id, greatest(at, :from) as start, least(coalesce(until, :to), :to) as finish
                        from seen
                        where state = 'online' and coalesce(until, :to) > :from
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                // A host online since before the window started has its change before it: look back as far as kept.
                .param("lookback", Timestamp.from(from.minus(java.time.Duration.ofDays(90))))
                .query(rs -> {
                    var start = rs.getTimestamp("start").toInstant();
                    var finish = rs.getTimestamp("finish").toInstant();
                    if (finish.isAfter(start)) {
                        intervals.add(new Interval(rs.getObject("host_id", UUID.class), start, finish));
                    }
                });
        return intervals;
    }

    /** How many streams the host opened each day in India, from {@code from}, newest first. */
    List<Daily> reconnects(UUID hostId, Instant from) {
        return jdbc.sql("""
                        select (at at time zone 'Asia/Kolkata')::date as day, count(*) as opened
                        from agentgateway.presence_changes
                        where host_id = :host and state = 'online' and at >= :from
                        group by 1 order by 1 desc
                        """)
                .param("host", hostId)
                .param("from", Timestamp.from(from))
                .query((rs, n) -> new Daily(rs.getObject("day", java.time.LocalDate.class), rs.getLong("opened")))
                .list();
    }

    record Daily(java.time.LocalDate day, long opened) {}
}
