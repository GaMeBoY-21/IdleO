package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RazorpayX against our payouts (ADR 0040). Every night, and when an admin asks, a day's payouts (India's day) are
 * listed from RazorpayX (https://razorpay.com/docs/api/x/payouts/fetch-all/, 100 a page) and compared one by one with
 * ours from that day:
 *
 * <ul>
 *   <li>{@code missing_ours}: RazorpayX paid something we have no payout for;
 *   <li>{@code missing_theirs}: we sent a payout RazorpayX doesn't have (one it didn't list is fetched by id first);
 *   <li>{@code amount}: RazorpayX's amount isn't our net;
 *   <li>{@code status}: RazorpayX's payout has ended differently from ours. One ours is merely behind on is moved on
 *       first, by the same path a webhook takes; only what's still different is an item.
 * </ul>
 *
 * Each item is kept once by its kind and subject; a later run that finds it right marks it fixed; an admin may accept
 * one, with a reason, audited. {@code payouts.reconciliation.open} must be 0 (the PayoutMismatch alert).
 */
@Service
class PayoutReconciliation {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconciliation.class);
    private static final Set<String> ENDED = Set.of("processed", "reversed", "failed", "rejected", "cancelled");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RazorpayXClient razorpayx;
    private final PayoutService payouts;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final PayoutsMetrics metrics;
    private final ZoneId zone;

    PayoutReconciliation(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RazorpayXClient razorpayx,
            PayoutService payouts,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            PayoutsMetrics metrics,
            @Value("${payouts.zone}") String zone) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.razorpayx = razorpayx;
        this.payouts = payouts;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
        this.metrics = metrics;
        this.zone = ZoneId.of(zone);
    }

    record Run(
            UUID id,
            LocalDate day,
            String trigger,
            Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable Integer payouts,
            @Nullable Integer found,
            @Nullable Integer open,
            @Nullable String failure) {}

    record Mismatch(
            UUID id,
            String kind,
            String subject,
            @Nullable UUID payoutId,
            @Nullable UUID userId,
            @Nullable Long ourPaise,
            @Nullable Long theirPaise,
            @Nullable String ourStatus,
            @Nullable String theirStatus,
            String detail,
            String state,
            Instant foundAt,
            Instant lastFoundAt,
            @Nullable Instant closedAt,
            @Nullable String closedBy,
            @Nullable String closeReason) {}

    /** Something found wrong, before it's kept. */
    private record Found(
            String kind,
            String subject,
            @Nullable UUID payoutId,
            @Nullable UUID userId,
            @Nullable Long ourPaise,
            @Nullable Long theirPaise,
            @Nullable String ourStatus,
            @Nullable String theirStatus,
            String detail) {}

    @Scheduled(cron = "${payouts.reconciliation-cron}", zone = "${payouts.zone}")
    void nightly() {
        if (!razorpayx.enabled()) {
            return;
        }
        var day = LocalDate.ofInstant(clock.instant(), zone).minusDays(1);
        try {
            run(day, "nightly");
        } catch (DuplicateKeyException claimed) {
            log.info("Payout reconciliation for {} is another instance's", day);
        }
    }

    /** An admin's run for a day (yesterday when none is given), audited with their reason. */
    Run run(@Nullable LocalDate asked, AuditTrail.Actor actor, String reason) {
        var day = asked == null ? LocalDate.ofInstant(clock.instant(), zone).minusDays(1) : asked;
        var run = run(day, actor.label());
        transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                actor,
                "payouts.reconcile",
                "payout_reconciliation",
                run.id().toString(),
                reason,
                null,
                Map.of("day", day.toString(), "found", String.valueOf(run.found())))));
        return run;
    }

    private Run run(LocalDate day, String trigger) {
        var id = ids.newId();
        jdbc.sql("""
                        insert into payouts.reconciliation_runs (id, day, trigger, started_at)
                        values (:id, :day, :trigger, :now)
                        """)
                .param("id", id)
                .param("day", day)
                .param("trigger", trigger)
                .param("now", Timestamp.from(now()))
                .update();
        var from = day.atStartOfDay(zone).toInstant();
        var to = day.plusDays(1).atStartOfDay(zone).toInstant();
        String failure = null;
        int compared = 0;
        var found = new ArrayList<Found>();
        var examined = new HashSet<String>();
        try {
            var theirs = razorpayx.payouts(from, to.minusSeconds(1));
            compared = theirs.size();
            found.addAll(compare(theirs, from, to, examined));
        } catch (RazorpayXClient.Refused | RazorpayXClient.Unreachable e) {
            failure = "RazorpayX couldn't be read: " + e.getMessage();
            log.warn("Payout reconciliation for {} couldn't read RazorpayX: {}", day, e.getMessage());
        }
        int kept = failure == null ? keep(id, found, examined) : 0;
        int open = countOpen();
        jdbc.sql("""
                        update payouts.reconciliation_runs
                        set finished_at = :now, payouts = :payouts, found = :found, open = :open, failure = :failure
                        where id = :id
                        """)
                .param("now", Timestamp.from(now()))
                .param("payouts", compared)
                .param("found", kept)
                .param("open", open)
                .param("failure", failure)
                .param("id", id)
                .update();
        metrics.openMismatches(open);
        return find(id).orElseThrow();
    }

    private List<Found> compare(List<RazorpayXClient.Payout> theirs, Instant from, Instant to, Set<String> examined) {
        var found = new ArrayList<Found>();
        var ours = jdbc.sql("select * from payouts.payouts where created_at >= :from and created_at < :to")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(PayoutService::payout)
                .list();
        var byId = new HashMap<UUID, PayoutService.Payout>();
        ours.forEach(payout -> byId.put(payout.id(), payout));
        var listed = new HashSet<UUID>();
        for (var their : theirs) {
            var ourId = ourId(their);
            var our = ourId == null
                    ? null
                    : byId.computeIfAbsent(ourId, id -> payouts.find(id).orElse(null));
            if (our == null) {
                examined.add(their.id());
                found.add(new Found(
                        "missing_ours",
                        their.id(),
                        null,
                        null,
                        null,
                        their.amount(),
                        null,
                        their.status(),
                        "RazorpayX has payout " + their.id() + " of " + their.amount()
                                + " paise that isn't one of ours."));
                continue;
            }
            listed.add(our.id());
            found.addAll(check(our, their, examined));
        }
        for (var our : ours) {
            if (listed.contains(our.id())) {
                continue;
            }
            if (our.razorpayxId() == null) {
                if (!our.status().equals("created")) {
                    continue;
                }
                examined.add(our.id().toString());
                found.add(new Found(
                        "missing_theirs",
                        our.id().toString(),
                        our.id(),
                        our.userId(),
                        our.netPaise(),
                        null,
                        our.status(),
                        null,
                        "Payout " + our.id() + " was never accepted by RazorpayX (" + our.attempts() + " tries: "
                                + our.lastError() + ")."));
                continue;
            }
            try {
                found.addAll(check(our, razorpayx.fetchPayout(our.razorpayxId()), examined));
            } catch (RazorpayXClient.Refused refused) {
                examined.add(our.razorpayxId());
                found.add(new Found(
                        "missing_theirs",
                        our.razorpayxId(),
                        our.id(),
                        our.userId(),
                        our.netPaise(),
                        null,
                        our.status(),
                        null,
                        "RazorpayX doesn't know payout " + our.razorpayxId() + ", which it gave us for " + our.id()
                                + "."));
            }
        }
        return found;
    }

    /** One of ours against RazorpayX's: moved on if it's merely behind, then compared. */
    private List<Found> check(PayoutService.Payout our, RazorpayXClient.Payout their, Set<String> examined) {
        examined.add(their.id());
        var found = new ArrayList<Found>();
        if (their.amount() != our.netPaise()) {
            found.add(new Found(
                    "amount",
                    their.id(),
                    our.id(),
                    our.userId(),
                    our.netPaise(),
                    their.amount(),
                    our.status(),
                    their.status(),
                    "RazorpayX paid " + their.amount() + " paise for payout " + our.id() + ", which is "
                            + our.netPaise() + "."));
        }
        if (!their.status().equals(our.status())) {
            payouts.applyRazorpayX(their);
            var now = payouts.find(our.id()).orElseThrow();
            if (ENDED.contains(their.status()) && !their.status().equals(now.status())) {
                found.add(new Found(
                        "status",
                        their.id(),
                        our.id(),
                        our.userId(),
                        our.netPaise(),
                        their.amount(),
                        now.status(),
                        their.status(),
                        "RazorpayX says payout " + our.id() + " is " + their.status() + "; ours is " + now.status()
                                + "."));
            } else if (!now.status().equals(our.status())) {
                log.warn(
                        "Payout {} was {} here and {} at RazorpayX: moved on; its webhook didn't arrive",
                        our.id(),
                        our.status(),
                        their.status());
            }
        }
        return found;
    }

    private static @Nullable UUID ourId(RazorpayXClient.Payout their) {
        try {
            return their.referenceId() == null ? null : UUID.fromString(their.referenceId());
        } catch (IllegalArgumentException notOurs) {
            return null;
        }
    }

    /** Keeps what was found (once by kind and subject) and marks fixed what this run examined and found right. */
    private int keep(UUID runId, List<Found> found, Set<String> examined) {
        var now = Timestamp.from(now());
        var keys = new HashSet<List<String>>();
        for (var item : found) {
            keys.add(List.of(item.kind(), item.subject()));
            jdbc.sql("""
                            insert into payouts.mismatches
                                (id, kind, subject, payout_id, user_id, our_paise, their_paise, our_status,
                                 their_status, detail, state, first_run_id, last_run_id, found_at, last_found_at)
                            values (:id, :kind, :subject, :payoutId, :userId, :ourPaise, :theirPaise, :ourStatus,
                                    :theirStatus, :detail, 'open', :runId, :runId, :now, :now)
                            on conflict (kind, subject) do update set
                                our_paise = excluded.our_paise, their_paise = excluded.their_paise,
                                our_status = excluded.our_status, their_status = excluded.their_status,
                                detail = excluded.detail, last_run_id = excluded.last_run_id,
                                last_found_at = excluded.last_found_at,
                                state = case when payouts.mismatches.state = 'accepted' then 'accepted' else 'open' end,
                                closed_at = case when payouts.mismatches.state = 'accepted'
                                                 then payouts.mismatches.closed_at end,
                                closed_by = case when payouts.mismatches.state = 'accepted'
                                                 then payouts.mismatches.closed_by end,
                                close_reason = case when payouts.mismatches.state = 'accepted'
                                                    then payouts.mismatches.close_reason end
                            """)
                    .param("id", ids.newId())
                    .param("kind", item.kind())
                    .param("subject", item.subject())
                    .param("payoutId", item.payoutId())
                    .param("userId", item.userId())
                    .param("ourPaise", item.ourPaise())
                    .param("theirPaise", item.theirPaise())
                    .param("ourStatus", item.ourStatus())
                    .param("theirStatus", item.theirStatus())
                    .param(
                            "detail",
                            item.detail().length() > 500 ? item.detail().substring(0, 500) : item.detail())
                    .param("runId", runId)
                    .param("now", now)
                    .update();
        }
        if (!examined.isEmpty()) {
            for (var open : list("open", 500)) {
                if (examined.contains(open.subject()) && !keys.contains(List.of(open.kind(), open.subject()))) {
                    jdbc.sql("""
                                    update payouts.mismatches
                                    set state = 'fixed', closed_at = :now, closed_by = 'reconciliation',
                                        close_reason = 'a later run found it right'
                                    where id = :id and state = 'open'
                                    """).param("now", now).param("id", open.id()).update();
                }
            }
        }
        return found.size();
    }

    /** An admin closes an item they've looked into, with why; audited in the same transaction. */
    Mismatch accept(UUID id, String reason, AuditTrail.Actor actor) {
        return Optional.ofNullable(transactions.execute(status -> {
                    var item = find(id, true).orElseThrow(() -> new IllegalArgumentException("No such item."));
                    if (!item.state().equals("open")) {
                        throw new IllegalArgumentException("This item is " + item.state() + " already.");
                    }
                    jdbc.sql("""
                                    update payouts.mismatches
                                    set state = 'accepted', closed_at = :now, closed_by = :by, close_reason = :reason
                                    where id = :id
                                    """)
                            .param("now", Timestamp.from(now()))
                            .param("by", actor.label())
                            .param("reason", reason)
                            .param("id", id)
                            .update();
                    audit.record(new AuditTrail.Entry(
                            actor,
                            "payouts.mismatch.accept",
                            "payout_mismatch",
                            id.toString(),
                            reason,
                            Map.of("state", "open"),
                            Map.of("state", "accepted")));
                    return find(id, false).orElseThrow();
                }))
                .orElseThrow();
    }

    /** Every instance's gauge, read from the table once a minute. */
    @Scheduled(fixedDelayString = "${payouts.reconciliation-count-every}")
    void count() {
        metrics.openMismatches(countOpen());
    }

    private int countOpen() {
        return jdbc.sql("select count(*) from payouts.mismatches where state = 'open'")
                .query(Integer.class)
                .single();
    }

    /** {@code state}: open, fixed, accepted or all; the newest found first. */
    List<Mismatch> list(String state, int limit) {
        return jdbc.sql("select * from payouts.mismatches where (:state = 'all' or state = :state)"
                        + " order by found_at desc, id desc limit " + limit)
                .param("state", state)
                .query(PayoutReconciliation::mismatch)
                .list();
    }

    List<Run> runs(int limit) {
        return jdbc.sql("select * from payouts.reconciliation_runs order by started_at desc, id desc limit " + limit)
                .query(PayoutReconciliation::run)
                .list();
    }

    Optional<Run> find(UUID id) {
        return jdbc.sql("select * from payouts.reconciliation_runs where id = :id")
                .param("id", id)
                .query(PayoutReconciliation::run)
                .optional();
    }

    private Optional<Mismatch> find(UUID id, boolean lock) {
        return jdbc.sql("select * from payouts.mismatches where id = :id" + (lock ? " for update" : ""))
                .param("id", id)
                .query(PayoutReconciliation::mismatch)
                .optional();
    }

    private static Run run(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var finished = rs.getTimestamp("finished_at");
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getObject("day", LocalDate.class),
                rs.getString("trigger"),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getObject("payouts", Integer.class),
                rs.getObject("found", Integer.class),
                rs.getObject("open", Integer.class),
                rs.getString("failure"));
    }

    private static Mismatch mismatch(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var closed = rs.getTimestamp("closed_at");
        return new Mismatch(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getString("subject"),
                rs.getObject("payout_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("our_paise", Long.class),
                rs.getObject("their_paise", Long.class),
                rs.getString("our_status"),
                rs.getString("their_status"),
                rs.getString("detail"),
                rs.getString("state"),
                rs.getTimestamp("found_at").toInstant(),
                rs.getTimestamp("last_found_at").toInstant(),
                closed == null ? null : closed.toInstant(),
                rs.getString("closed_by"),
                rs.getString("close_reason"));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
