package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.agentgateway.SessionSignals;
import com.idleo.controlplane.billing.SessionRefunds;
import com.idleo.controlplane.identity.AccountHolds;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.sessions.SessionOutcomes;
import gpu.agent.v1.Session.AbuseSignal;
import gpu.agent.v1.Session.UsageReport;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Software no renter may run on a host's GPU, and what to do about it (ADR 0038). A host's runner flags a session, or stops
 * it, for a listed build, a listed name or a mining pool's domain; each minute the agent also says what the job did. Both
 * arrive here (the {@link SessionSignals} port) and become one review item for the session, with every signal on it.
 *
 * <p><b>Only a high-confidence signal acts without an admin</b>: a session the runner stopped (a listed build, or a second
 * lookup of a pool) has its renter's account suspended at once, pending review. A name, a first lookup, dropped connections
 * and the mining-like pattern of behaviour (high GPU use, tiny GPU memory, near-zero PCIe traffic, steady small network
 * traffic, 15 minutes running, on a "Run your code" session) only open an item, which never stops a job: a false stop costs
 * an honest renter their work.
 *
 * <p>An admin <b>confirms</b> an item (the suspension stays; perhaps a ban, which only clearing the item lifts), <b>clears</b>
 * it (the account is reinstated and the session's charge refunded, one ledger transaction naming the item) or adds a note;
 * each is one audit row with a typed reason. The host was paid for the time the GPU was used; a refund comes from the
 * platform's commission first and then the hosts' shares, as a report's does.
 */
@Service
class AbuseReviews implements SessionSignals {

    private static final Logger log = LoggerFactory.getLogger(AbuseReviews.class);

    /** The services whose behaviour is watched for the mining-like pattern: code the renter wrote, run as it is. */
    static final Set<String> PATTERN_SERVICES = Set.of("Run your code");
    /** How many minutes in a row the pattern must hold before an item is opened. */
    static final int PATTERN_MINUTES = 15;
    /** GPU use at least this, percent. */
    static final int PATTERN_MIN_UTIL = 90;
    /** GPU memory in use at most this, percent: a miner needs next to none of it. */
    static final int PATTERN_MAX_MEMORY = 10;
    /** PCIe traffic at most this, KB/s: nothing is going to or from the GPU. */
    static final int PATTERN_MAX_PCIE = 1_000;
    /** The network carries at least this and at most this, bytes a minute, both directions: a pool's small, steady chatter. */
    static final long PATTERN_MIN_NET = 200;

    static final long PATTERN_MAX_NET = 2_000_000;

    /** The system actor for what happens without an admin. */
    private static final AuditTrail.Actor WATCH = AuditTrail.Actor.system("abuse-watch");

    /** A review item. */
    record Item(
            UUID id,
            UUID sessionId,
            int attempt,
            UUID renterId,
            @Nullable UUID hostId,
            String state,
            String action,
            boolean banned,
            Instant createdAt,
            Instant updatedAt,
            @Nullable Instant resolvedAt,
            @Nullable UUID resolvedBy,
            @Nullable String resolution,
            @Nullable UUID refundTxn,
            @Nullable Long refundPaise) {}

    /** One signal on an item. */
    record Signal(UUID id, String kind, String detail, String sha256, long count, boolean stopped, Instant at) {}

    /** One note on an item. */
    record Note(UUID id, UUID authorId, String note, Instant at) {}

    /** What an admin's action came to. */
    sealed interface Result {
        record Done(Item item) implements Result {}

        record NotFound() implements Result {}

        record Refused(String why) implements Result {}
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final SessionOutcomes outcomes;
    private final SessionRefunds refunds;
    private final AccountHolds holds;
    private final AuditTrail audit;
    private final AbuseMetrics metrics;
    private final IdGenerator ids;
    private final Clock clock;

    AbuseReviews(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            SessionOutcomes outcomes,
            SessionRefunds refunds,
            AccountHolds holds,
            AuditTrail audit,
            AbuseMetrics metrics,
            IdGenerator ids,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.outcomes = outcomes;
        this.refunds = refunds;
        this.holds = holds;
        this.audit = audit;
        this.metrics = metrics;
        this.ids = ids;
        this.clock = clock;
    }

    /** The ledger transaction of an item's refund: one an item, so clearing it again can never pay twice. */
    static UUID refundTxnId(UUID itemId) {
        return UUID.nameUUIDFromBytes(("abuse-refund:" + itemId).getBytes(StandardCharsets.UTF_8));
    }

    // ---- What arrives from a host's agent ----

    @Override
    public void onAbuse(UUID hostId, AbuseSignal signal) {
        var sessionId = UUID.fromString(signal.getSessionId());
        var kind = switch (signal.getKind()) {
            case KIND_PROCESS_NAME -> "process_name";
            case KIND_PROCESS_HASH -> "process_hash";
            case KIND_POOL_LOOKUP -> "pool_lookup";
            case KIND_CONNECTION_DROPS -> "connection_drops";
            default -> throw new IllegalArgumentException("a signal of a kind we don't know");
        };
        record(
                hostId,
                sessionId,
                signal.getAttempt(),
                kind,
                signal.getDetail(),
                signal.getSha256(),
                signal.getCount(),
                signal.getStopped());
    }

    @Override
    public void onBehaviour(UUID hostId, UsageReport report) {
        var sessionId = UUID.fromString(report.getSessionId());
        if (ranOn(hostId, sessionId, report.getAttempt()).isEmpty()) {
            return;
        }
        var now = Timestamp.from(Instant.now(clock));
        int stored = jdbc.sql("""
                        insert into trust.behavior_samples
                            (session_id, attempt, minute_index, host_id, util_pct, memory_pct, pcie_kb_s, net_out, net_in,
                             printed, conn_drops, at)
                        values (:session, :attempt, :minute, :host, :util, :memory, :pcie, :out, :in, :printed, :drops, :now)
                        on conflict do nothing
                        """)
                .param("session", sessionId)
                .param("attempt", report.getAttempt())
                .param("minute", report.getMinuteIndex())
                .param("host", hostId)
                .param("util", report.getAvgUtilPct())
                .param("memory", report.getAvgMemoryPct())
                .param("pcie", report.getAvgPcieKbPerS())
                .param("out", report.getNetworkOutBytes())
                .param("in", report.getNetworkInBytes())
                .param("printed", report.getPrintedLines())
                .param("drops", report.getConnectionsDropped())
                .param("now", now)
                .update();
        if (stored == 1) {
            looksLikeMining(hostId, sessionId, report.getAttempt(), report.getMinuteIndex());
        }
    }

    /**
     * Whether the last {@value #PATTERN_MINUTES} minutes, every one, look like mining: opens an item (never stops anything).
     */
    private void looksLikeMining(UUID hostId, UUID sessionId, int attempt, int minute) {
        var outcome = outcomes.of(sessionId);
        if (outcome.isEmpty()
                || !PATTERN_SERVICES.contains(
                        Objects.requireNonNullElse(outcome.get().serviceName(), ""))) {
            return;
        }
        var window = jdbc.sql("""
                        select count(*) n, min(minute_index) lo, max(minute_index) hi, min(util_pct) util,
                               max(memory_pct) memory, max(pcie_kb_s) pcie, min(net_out + net_in) net_low,
                               max(net_out + net_in) net_high
                        from (select * from trust.behavior_samples
                              where session_id = :session and attempt = :attempt and minute_index <= :minute
                              order by minute_index desc limit :minutes) w
                        """)
                .param("session", sessionId)
                .param("attempt", attempt)
                .param("minute", minute)
                .param("minutes", PATTERN_MINUTES)
                .query((rs, n) -> new long[] {
                    rs.getLong("n"),
                    rs.getLong("lo"),
                    rs.getLong("hi"),
                    rs.getLong("util"),
                    rs.getLong("memory"),
                    rs.getLong("pcie"),
                    rs.getLong("net_low"),
                    rs.getLong("net_high")
                })
                .single();
        boolean pattern = window[0] == PATTERN_MINUTES
                && window[2] - window[1] == PATTERN_MINUTES - 1
                && window[3] >= PATTERN_MIN_UTIL
                && window[4] <= PATTERN_MAX_MEMORY
                && window[5] <= PATTERN_MAX_PCIE
                && window[6] >= PATTERN_MIN_NET
                && window[7] <= PATTERN_MAX_NET;
        if (pattern) {
            record(
                    hostId,
                    sessionId,
                    attempt,
                    "mining_pattern",
                    "%d minutes: GPU %d%% or more, memory %d%% or less, PCIe %d KB/s or less, network %d to %d bytes a minute"
                            .formatted(PATTERN_MINUTES, window[3], window[4], window[5], window[6], window[7]),
                    "",
                    0,
                    false);
        }
    }

    /** The renter of a session, if the host really ran that attempt; else empty (and said in the log). */
    private Optional<SessionOutcomes.Outcome> ranOn(UUID hostId, UUID sessionId, int attempt) {
        var outcome = outcomes.of(sessionId);
        if (outcome.isEmpty()) {
            return Optional.empty();
        }
        if (!outcomes.hostOfAttempt(sessionId, attempt).filter(hostId::equals).isPresent()) {
            log.warn(
                    "Host {} reported about attempt {} of session {}, which it did not run; ignored",
                    hostId,
                    attempt,
                    sessionId);
            return Optional.empty();
        }
        return outcome;
    }

    /**
     * Puts a signal on its session's item, making the item if it is the first. A signal the runner stopped the session for
     * suspends the renter's account while the item is open, once. A signal sent again is the same row and counts once.
     */
    private void record(
            UUID hostId,
            UUID sessionId,
            int attempt,
            String kind,
            String detail,
            String sha256,
            long count,
            boolean stopped) {
        var outcome = ranOn(hostId, sessionId, attempt);
        if (outcome.isEmpty()) {
            return;
        }
        var renterId = outcome.get().renterId();
        transactions.executeWithoutResult(status -> {
            var now = Timestamp.from(Instant.now(clock));
            var itemId = jdbc.sql("""
                            insert into trust.abuse_items
                                (id, session_id, attempt, renter_id, host_id, state, action, created_at, updated_at)
                            values (:id, :session, :attempt, :renter, :host, 'open', 'none', :now, :now)
                            on conflict (session_id) do update set updated_at = excluded.updated_at
                            returning id
                            """)
                    .param("id", ids.newId())
                    .param("session", sessionId)
                    .param("attempt", attempt)
                    .param("renter", renterId)
                    .param("host", hostId)
                    .param("now", now)
                    .query(UUID.class)
                    .single();
            int inserted = jdbc.sql("""
                            insert into trust.abuse_signals (id, item_id, kind, detail, sha256, count, stopped, at)
                            values (:id, :item, :kind, :detail, :sha256, :count, :stopped, :now)
                            on conflict do nothing
                            """)
                    .param("id", ids.newId())
                    .param("item", itemId)
                    .param("kind", kind)
                    .param("detail", detail)
                    .param("sha256", sha256)
                    .param("count", count)
                    .param("stopped", stopped)
                    .param("now", now)
                    .update();
            if (inserted == 0) {
                return;
            }
            if (stopped) {
                metrics.stop(kind);
                suspendWhileOpen(itemId, renterId);
            } else {
                metrics.flag(kind);
            }
        });
    }

    /** The runner stopped the session: the renter's account is suspended pending review, if the item is still open. */
    private void suspendWhileOpen(UUID itemId, UUID renterId) {
        var state = jdbc.sql("select state from trust.abuse_items where id = :id for update")
                .param("id", itemId)
                .query(String.class)
                .single();
        String action = "stopped";
        if (state.equals("open")) {
            try {
                holds.hold(
                        renterId,
                        "stopped for running software that isn't allowed on IdleO, pending review of item " + itemId,
                        WATCH);
                action = "suspended";
            } catch (IllegalStateException e) {
                // A waiting, erased or already banned account can't be suspended; the session was stopped all the same.
                log.info("Renter {} was not suspended for review item {}: {}", renterId, itemId, e.getMessage());
            }
        }
        jdbc.sql("update trust.abuse_items set action = :action where id = :id and action <> 'suspended'")
                .param("action", action)
                .param("id", itemId)
                .update();
    }

    // ---- What an admin reads and decides ----

    Optional<Item> find(UUID id) {
        return jdbc.sql("select * from trust.abuse_items where id = :id")
                .param("id", id)
                .query(AbuseReviews::item)
                .optional();
    }

    List<Signal> signals(UUID itemId) {
        return jdbc.sql("select * from trust.abuse_signals where item_id = :id order by at, id")
                .param("id", itemId)
                .query((rs, n) -> new Signal(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("detail"),
                        rs.getString("sha256"),
                        rs.getLong("count"),
                        rs.getBoolean("stopped"),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    List<Note> notes(UUID itemId) {
        return jdbc.sql("select * from trust.abuse_notes where item_id = :id order by at, id")
                .param("id", itemId)
                .query((rs, n) -> new Note(
                        rs.getObject("id", UUID.class),
                        rs.getObject("author_id", UUID.class),
                        rs.getString("note"),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    /**
     * Confirms an open item: the suspension stays, and the account is banned if {@code ban}. A confirmed item that was not
     * banned can be banned later by confirming it again with {@code ban}.
     */
    Result confirm(UUID id, boolean ban, String reason, AuditTrail.Actor actor) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var item = lock(id);
            if (item == null) {
                return new Result.NotFound();
            }
            if (item.state().equals("cleared")) {
                return new Result.Refused("This review was cleared already.");
            }
            if (item.state().equals("confirmed") && (!ban || item.banned())) {
                return new Result.Done(item);
            }
            boolean banned = item.banned();
            if (ban) {
                try {
                    holds.ban(item.renterId(), reason, actor);
                    banned = true;
                } catch (IllegalStateException e) {
                    return new Result.Refused("The account can't be banned: " + e.getMessage());
                }
            }
            var now = Timestamp.from(Instant.now(clock));
            jdbc.sql("""
                            update trust.abuse_items
                            set state = 'confirmed', banned = :banned, updated_at = :now,
                                resolved_at = coalesce(resolved_at, :now), resolved_by = :by, resolution = :reason
                            where id = :id
                            """)
                    .param("banned", banned)
                    .param("now", now)
                    .param("by", actor.userId())
                    .param("reason", reason)
                    .param("id", id)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    "abuse.confirm",
                    "abuse_item",
                    id.toString(),
                    reason,
                    Map.of("state", item.state(), "banned", item.banned()),
                    Map.of("state", "confirmed", "banned", banned)));
            return new Result.Done(find(id).orElseThrow());
        }));
    }

    /**
     * Clears an item: the account is reinstated (a ban lifts only here) and everything the session cost its renter, net of
     * what was given back before, is refunded in one ledger transaction named by the item. Clearing again changes nothing.
     */
    Result clear(UUID id, String reason, AuditTrail.Actor actor) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var item = lock(id);
            if (item == null) {
                return new Result.NotFound();
            }
            if (item.state().equals("cleared")) {
                return new Result.Done(item);
            }
            boolean reinstated;
            try {
                reinstated = holds.lift(item.renterId(), reason, actor);
            } catch (IllegalStateException e) {
                return new Result.Refused("The account can't be reinstated: " + e.getMessage());
            }
            SessionRefunds.Refund refund = null;
            var paid = refunds.paid(item.sessionId()).renterPaid();
            if (paid.isPositive()) {
                refund = refunds.refund(item.sessionId(), item.renterId(), refundTxnId(id), paid);
            }
            var now = Timestamp.from(Instant.now(clock));
            jdbc.sql("""
                            update trust.abuse_items
                            set state = 'cleared', banned = false, updated_at = :now, resolved_at = :now, resolved_by = :by,
                                resolution = :reason, refund_txn = :txn, refund_paise = :paise
                            where id = :id
                            """)
                    .param("now", now)
                    .param("by", actor.userId())
                    .param("reason", reason)
                    .param("txn", refund == null ? null : refund.txnId())
                    .param(
                            "paise",
                            refund == null ? null : refund.split().amount().value())
                    .param("id", id)
                    .update();
            var after = new LinkedHashMap<String, Object>();
            after.put("state", "cleared");
            after.put("accountReinstated", reinstated);
            if (refund != null) {
                after.put("refundPaise", refund.split().amount().value());
                after.put("refundTxn", refund.txnId().toString());
            }
            audit.record(new AuditTrail.Entry(
                    actor,
                    "abuse.clear",
                    "abuse_item",
                    id.toString(),
                    reason,
                    Map.of("state", item.state(), "banned", item.banned()),
                    after));
            return new Result.Done(find(id).orElseThrow());
        }));
    }

    /** Adds a note; the note is its own reason. */
    Result note(UUID id, String note, AuditTrail.Actor actor) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var item = lock(id);
            if (item == null) {
                return new Result.NotFound();
            }
            jdbc.sql(
                            "insert into trust.abuse_notes (id, item_id, author_id, note, at) values (:id, :item, :author, :note, :now)")
                    .param("id", ids.newId())
                    .param("item", id)
                    .param("author", actor.userId())
                    .param("note", note)
                    .param("now", Timestamp.from(Instant.now(clock)))
                    .update();
            audit.record(new AuditTrail.Entry(actor, "abuse.note", "abuse_item", id.toString(), note, null, null));
            return new Result.Done(item);
        }));
    }

    private @Nullable Item lock(UUID id) {
        return jdbc.sql("select * from trust.abuse_items where id = :id for update")
                .param("id", id)
                .query(AbuseReviews::item)
                .optional()
                .orElse(null);
    }

    static Item item(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Item(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getInt("attempt"),
                rs.getObject("renter_id", UUID.class),
                rs.getObject("host_id", UUID.class),
                rs.getString("state"),
                rs.getString("action"),
                rs.getBoolean("banned"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("resolved_at") == null
                        ? null
                        : rs.getTimestamp("resolved_at").toInstant(),
                rs.getObject("resolved_by", UUID.class),
                rs.getString("resolution"),
                rs.getObject("refund_txn", UUID.class),
                rs.getObject("refund_paise") == null ? null : rs.getLong("refund_paise"));
    }

    /** What the renter paid for the session, net of what was given back, for the admin's page. */
    Paise paid(UUID sessionId) {
        return refunds.paid(sessionId).renterPaid();
    }
}
