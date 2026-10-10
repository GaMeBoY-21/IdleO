package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.hosts.HostRemoved;
import com.idleo.controlplane.hosts.HostWork;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an owner does with a machine after adding it (ADR 0023): rename it, pause and resume it, remove it. Each locks
 * the host's row, so it takes turns with a booking, and each answers empty for a host that isn't the owner's, and
 * fails with {@code host-removed} for one that was removed.
 */
@Service
class HostLifecycle {

    static final Duration LONGEST_PAUSE = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final HostRepository hosts;
    private final HostWork work;
    private final ApplicationEventPublisher events;
    private final IdGenerator ids;
    private final Clock clock;

    HostLifecycle(
            JdbcClient jdbc,
            HostRepository hosts,
            HostWork work,
            ApplicationEventPublisher events,
            IdGenerator ids,
            Clock clock) {
        this.jdbc = jdbc;
        this.hosts = hosts;
        this.work = work;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    Optional<Host> rename(UUID owner, UUID hostId, String name) {
        return change(owner, hostId, "name = :name", update -> update.param("name", name));
    }

    /**
     * Stops new offers to the host until {@code until}, or until it's resumed when that's null. A running session
     * finishes.
     */
    @Transactional
    Optional<Host> pause(UUID owner, UUID hostId, @Nullable Instant until) {
        var now = Instant.now(clock);
        if (until != null && !until.isAfter(now)) {
            throw ApiProblem.invalid("until", "must be in the future.");
        }
        if (until != null && until.isAfter(now.plus(LONGEST_PAUSE))) {
            throw ApiProblem.of(Problem.PAUSE_TOO_LONG, "The pause must end within 7 days; it ends " + until + ".");
        }
        return change(
                owner,
                hostId,
                "paused_at = :now, paused_until = :until",
                update -> update.param("now", Timestamp.from(now))
                        .param("until", until == null ? null : Timestamp.from(until), java.sql.Types.TIMESTAMP));
    }

    @Transactional
    Optional<Host> resume(UUID owner, UUID hostId) {
        return change(owner, hostId, "paused_at = null, paused_until = null", update -> update);
    }

    /**
     * Removes the host for good: refused with {@code host-busy} while it runs a session; otherwise its certificates
     * are revoked, its enrolment code is gone, and {@link HostRemoved} closes its stream. The row stays.
     */
    @Transactional
    Optional<Host> remove(UUID owner, UUID hostId) {
        var host = lock(owner, hostId);
        if (host.isEmpty()) {
            return Optional.empty();
        }
        if (work.isBusy(hostId)) {
            throw ApiProblem.of(Problem.HOST_BUSY, "This host is running a session; remove it when that's over.");
        }
        var now = Timestamp.from(Instant.now(clock));
        jdbc.sql("""
                        update hosts.hosts
                        set status = 'removed', removed_at = :now, paused_at = null, paused_until = null,
                            updated_at = :now, version = version + 1
                        where id = :hostId
                        """).param("now", now).param("hostId", hostId).update();
        jdbc.sql("update hosts.host_certificates set revoked_at = :now where host_id = :hostId and revoked_at is null")
                .param("now", now)
                .param("hostId", hostId)
                .update();
        jdbc.sql("delete from hosts.enrolment_codes where host_id = :hostId")
                .param("hostId", hostId)
                .update();
        jdbc.sql("""
                        insert into hosts.status_changes (id, host_id, from_status, to_status, reason, changed_by, created_at)
                        values (:id, :hostId, :from, 'removed', 'removed by its owner', :by, :now)
                        """)
                .param("id", ids.newId())
                .param("hostId", hostId)
                .param("from", host.get().status())
                .param("by", "owner " + owner)
                .param("now", now)
                .update();
        events.publishEvent(new HostRemoved(hostId));
        return hosts.findById(hostId);
    }

    private Optional<Host> change(
            UUID owner, UUID hostId, String set, java.util.function.UnaryOperator<JdbcClient.StatementSpec> params) {
        if (lock(owner, hostId).isEmpty()) {
            return Optional.empty();
        }
        params.apply(jdbc.sql("update hosts.hosts set " + set
                                + ", updated_at = :updatedAt, version = version + 1 where id = :hostId")
                        .param("updatedAt", Timestamp.from(Instant.now(clock)))
                        .param("hostId", hostId))
                .update();
        return hosts.findById(hostId);
    }

    private Optional<Host> lock(UUID owner, UUID hostId) {
        var host = jdbc.sql("select status from hosts.hosts where id = :hostId and owner_user_id = :owner for update")
                .param("hostId", hostId)
                .param("owner", owner)
                .query(String.class)
                .optional();
        if (host.isPresent() && HostStatus.REMOVED.dbValue().equals(host.get())) {
            throw ApiProblem.of(Problem.HOST_REMOVED, "This host was removed.");
        }
        return host.flatMap(status -> hosts.findById(hostId));
    }
}
