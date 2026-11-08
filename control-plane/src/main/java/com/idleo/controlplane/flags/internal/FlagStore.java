package com.idleo.controlplane.flags.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Flag;
import com.idleo.controlplane.Flags;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.ValkeyHints;
import com.idleo.controlplane.identity.AdminAccess;
import com.idleo.controlplane.identity.AdminNoticed;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The flags (ADR 0056): the one reader every module asks through {@link Flags}, and the one writer of {@code
 * flags.flags}. A change commits with its audit row and the Owners' notice, then a hint on {@link #CHANNEL} tells every
 * instance to read the flags again, at once; each also reads them every {@code flags.refresh-every}, so a lost hint, or
 * Valkey being down, costs at most that.
 */
@Service
@EnableConfigurationProperties(FlagsProperties.class)
class FlagStore implements Flags, MessageListener {

    static final String CHANNEL = "flags:changed";

    private static final Logger log = LoggerFactory.getLogger(FlagStore.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;
    private final AuditTrail audit;
    private final AdminAccess admins;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final IdGenerator ids;
    private final FlagCache cache;

    FlagStore(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            StringRedisTemplate redis,
            AuditTrail audit,
            AdminAccess admins,
            ApplicationEventPublisher events,
            Clock clock,
            IdGenerator ids,
            FlagsProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.redis = redis;
        this.audit = audit;
        this.admins = admins;
        this.events = events;
        this.clock = clock;
        this.ids = ids;
        this.cache = new FlagCache(this::readAll, clock, properties.trustFor());
        cache.reload();
    }

    /** One flag as the console shows it. */
    record Stored(
            Flag flag,
            boolean paused,
            @Nullable Instant until,
            boolean resumeAutomatically,
            @Nullable String reason,
            @Nullable UUID changedBy,
            @Nullable Instant changedAt,
            long version) {}

    @Override
    public Optional<Pause> pause(Flag flag) {
        return cache.pause(flag);
    }

    @Override
    public void require(Flag flag) {
        cache.pause(flag).ifPresent(pause -> {
            throw Flags.refusal(pause, clock.instant());
        });
    }

    /** Reads the flags again, on this instance's timer: the backstop for a lost hint. */
    @Scheduled(fixedDelayString = "${flags.refresh-every:30s}", initialDelayString = "${flags.refresh-every:30s}")
    void refresh() {
        cache.reload();
    }

    /** Another instance (or this one) changed a flag. */
    @Override
    public void onMessage(Message message, byte @Nullable [] pattern) {
        cache.reload();
    }

    /** Every flag, from the database, for the console. */
    List<Stored> stored() {
        return jdbc.sql("select * from flags.flags").query(this::row).list().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparing(row -> row.flag().ordinal()))
                .toList();
    }

    Optional<Stored> stored(Flag flag) {
        return jdbc.sql("select * from flags.flags where key = :key")
                .param("key", flag.key())
                .query(this::row)
                .optional();
    }

    /**
     * Pauses {@code flag} now. {@code until}: when it's expected back; it runs again by itself then only when {@code
     * resumeAutomatically}. Pausing a paused flag changes its time and reason.
     */
    Stored pause(
            Flag flag, @Nullable Instant until, boolean resumeAutomatically, String reason, AuditTrail.Actor actor) {
        var now = clock.instant();
        if (until != null && !until.isAfter(now)) {
            throw ApiProblem.invalid("until", "Choose a time in the future.");
        }
        if (resumeAutomatically && until == null) {
            throw ApiProblem.invalid("until", "Say when it should run again by itself.");
        }
        transactions.executeWithoutResult(status -> {
            var before = lock(flag);
            jdbc.sql("""
                            update flags.flags set paused = true, until = :until, resume_automatically = :auto,
                                reason = :reason, changed_by = :by, changed_at = :now
                            where key = :key
                            """)
                    .param("until", until == null ? null : Timestamp.from(until))
                    .param("auto", resumeAutomatically)
                    .param("reason", reason)
                    .param("by", actor.userId())
                    .param("now", Timestamp.from(now))
                    .param("key", flag.key())
                    .update();
            var after = new LinkedHashMap<String, Object>();
            after.put("paused", true);
            if (until != null) {
                after.put("until", until.toString());
                after.put("resumeAutomatically", resumeAutomatically);
            }
            changed(flag, before, after, reason, actor, "flag.pause");
        });
        return stored(flag).orElseThrow();
    }

    /** Runs {@code flag} again: once approved, or when its time is up. Nothing when it's running already. */
    void resume(Flag flag, String reason, AuditTrail.Actor actor) {
        transactions.executeWithoutResult(status -> {
            var before = lock(flag);
            if (!before.paused()) {
                throw ApiProblem.of(Problem.ACTION_REFUSED, flag.key() + " is running already.");
            }
            resumeLocked(flag, before, reason, actor);
        });
    }

    private void resumeLocked(Flag flag, Stored before, String reason, AuditTrail.Actor actor) {
        jdbc.sql("""
                        update flags.flags set paused = false, until = null, resume_automatically = false,
                            reason = :reason, changed_by = :by, changed_at = :now
                        where key = :key
                        """)
                .param("reason", reason)
                .param("by", actor.userId())
                .param("now", Timestamp.from(clock.instant()))
                .param("key", flag.key())
                .update();
        changed(flag, before, Map.of("paused", false), reason, actor, "flag.resume");
    }

    /**
     * Lifts every pause whose time is up and that runs again by itself: safe on every instance at once, since each row is
     * locked and checked again before it changes.
     */
    @Scheduled(fixedDelayString = "${flags.expire-every:15s}", initialDelayString = "${flags.expire-every:15s}")
    void expire() {
        try {
            var due = jdbc.sql("""
                            select key from flags.flags
                            where paused and resume_automatically and until <= :now
                            """)
                    .param("now", Timestamp.from(clock.instant()))
                    .query(String.class)
                    .list();
            for (var key : due) {
                var flag = Flag.of(key).orElse(null);
                if (flag == null) {
                    continue;
                }
                transactions.executeWithoutResult(status -> {
                    var before = jdbc.sql("""
                                    select * from flags.flags
                                    where key = :key and paused and resume_automatically and until <= :now
                                    for update skip locked
                                    """)
                            .param("key", key)
                            .param("now", Timestamp.from(clock.instant()))
                            .query(this::row)
                            .optional()
                            .orElse(null);
                    if (before != null) {
                        resumeLocked(
                                flag,
                                before,
                                "its time was up: it was paused until then",
                                AuditTrail.Actor.system("flag expiry"));
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("Lifting expired pauses failed; it runs again shortly (the cache lifts them on time)", e);
        }
    }

    private Stored lock(Flag flag) {
        return jdbc.sql("select * from flags.flags where key = :key for update")
                .param("key", flag.key())
                .query(this::row)
                .optional()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such flag."));
    }

    /** The audit row, the Owners' notice when the flag says so, and the hint once it all commits. */
    private void changed(
            Flag flag, Stored before, Map<String, Object> after, String reason, AuditTrail.Actor actor, String action) {
        var was = new LinkedHashMap<String, Object>();
        was.put("paused", before.paused());
        if (before.until() != null) {
            was.put("until", before.until().toString());
        }
        audit.record(new AuditTrail.Entry(actor, action, "flag", flag.key(), reason, was, after));
        if (flag.emailsOwners()) {
            boolean paused = Boolean.TRUE.equals(after.get("paused"));
            var id = ids.newId();
            for (var owner : admins.owners()) {
                events.publishEvent(new AdminNoticed(
                        owner,
                        "flag:" + flag.key() + ":" + id + ":" + owner,
                        paused ? flag.key() + " was paused" : flag.key() + " is running again",
                        actor.name() + (paused ? " paused " : " ran ") + flag.key() + (paused ? "" : " again") + ": "
                                + reason + ". Sign in to the console to see the flags.",
                        "/admin/flags/"));
            }
        }
        ValkeyHints.afterCommit(this::hint);
    }

    private void hint() {
        try {
            redis.convertAndSend(CHANNEL, "changed");
        } catch (RuntimeException valkeyDown) {
            // Each instance reads the flags again within refresh-every anyway; this one does now.
            log.info(
                    "Couldn't send the flags' hint ({}); instances read them within their refresh",
                    valkeyDown.toString());
            cache.reload();
        }
    }

    private Map<Flag, FlagCache.Row> readAll() {
        var rows = new EnumMap<Flag, FlagCache.Row>(Flag.class);
        for (var row : stored()) {
            rows.put(row.flag(), new FlagCache.Row(row.flag(), row.paused(), row.until(), row.resumeAutomatically()));
        }
        return rows;
    }

    /** Null for a flag a newer release added, which this one leaves alone (ADR 0046). */
    private @Nullable Stored row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        var until = rs.getTimestamp("until");
        var changedAt = rs.getTimestamp("changed_at");
        var flag = Flag.of(rs.getString("key")).orElse(null);
        if (flag == null) {
            return null;
        }
        return new Stored(
                flag,
                rs.getBoolean("paused"),
                until == null ? null : until.toInstant(),
                rs.getBoolean("resume_automatically"),
                rs.getString("reason"),
                rs.getObject("changed_by", UUID.class),
                changedAt == null ? null : changedAt.toInstant(),
                rs.getLong("version"));
    }
}
