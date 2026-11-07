package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payout runs (ADR 0040), each one batch (ADR 0055): every Monday at 10:00 in India a batch is prepared, a preview of
 * everyone with a UPI ID payouts may go to: what each would be paid (the gross, the tax, the fee, what's recovered, what's
 * held, the net and the masked UPI ID), or why they're left out. Nothing moves until the batch is released, by one
 * approved action; release checks every quote again, refuses if any changed (the preview is refreshed first), and pays
 * each as previewed, once. A batch is released once. One instance claims the week's batch (a unique row a payday); a
 * second finds it claimed and does nothing. An admin may prepare a batch at once, or pay one person again after a payout
 * failed; a deletion's final payout goes at once, whatever the amount above RazorpayX's ₹1. There's no "pay me now"
 * for hosts.
 */
@Service
class PayoutRuns {

    private static final Logger log = LoggerFactory.getLogger(PayoutRuns.class);

    /** RazorpayX's smallest payout (https://razorpay.com/docs/api/x/payouts/create/vpa/): 100 paise. */
    static final Paise RAZORPAYX_MINIMUM = Paise.of(100);

    private final JdbcClient jdbc;
    private final PayoutService payouts;
    private final RazorpayXClient razorpayx;
    private final PayoutsProperties properties;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final ZoneId zone;
    private final CronExpression weeklyCron;
    private final TransactionTemplate transactions;

    PayoutRuns(
            JdbcClient jdbc,
            PayoutService payouts,
            RazorpayXClient razorpayx,
            PayoutsProperties properties,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            PlatformTransactionManager transactionManager,
            @Value("${payouts.zone}") String zone,
            @Value("${payouts.weekly-cron}") String weeklyCron) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.weeklyCron = CronExpression.parse(weeklyCron);
        this.jdbc = jdbc;
        this.payouts = payouts;
        this.razorpayx = razorpayx;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
        this.zone = ZoneId.of(zone);
    }

    record Run(
            UUID id,
            String kind,
            LocalDate payday,
            String trigger,
            Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable Integer owners,
            @Nullable Integer payouts,
            @Nullable Long totalPaise,
            @Nullable String failure,
            String state,
            long version,
            @Nullable Instant releasedAt,
            @Nullable String releasedBy) {

        boolean released() {
            return "released".equals(state);
        }
    }

    /** One host in a batch: the preview's quote, and the payout release made, when it has. */
    record Item(
            UUID runId,
            UUID userId,
            PayoutService.Quote quote,
            @Nullable UUID payoutId) {}

    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** The least a weekly payout pays. */
    long minimum() {
        return properties.minimumPaise();
    }

    /** When the next weekly run starts, from {@code payouts.weekly-cron} (Monday 10:00 in India). */
    Instant nextPayday() {
        return java.util.Objects.requireNonNull(weeklyCron.next(clock.instant().atZone(zone)))
                .toInstant();
    }

    @Scheduled(cron = "${payouts.weekly-cron}", zone = "${payouts.zone}")
    void weekly() {
        if (!razorpayx.enabled()) {
            return;
        }
        var payday = clock.instant().atZone(zone).toLocalDate();
        var run = claim("weekly", payday, "weekly");
        if (run.isEmpty()) {
            log.info("The payout run for {} is another instance's, or done", payday);
            return;
        }
        prepare(run.get());
    }

    /** An admin starts a run now, outside the schedule, with a reason, audited. */
    @Transactional
    Run startNow(String reason, AuditTrail.Actor actor) {
        var run = claim("manual", clock.instant().atZone(zone).toLocalDate(), actor.label())
                .orElseThrow();
        audit.record(new AuditTrail.Entry(
                actor,
                "payout_run.start",
                "payout_run",
                run.id().toString(),
                reason,
                null,
                Map.of("payday", run.payday().toString())));
        return run;
    }

    /**
     * The batch's preview, made again: each candidate's quote now. A released batch isn't changed. The batch's version
     * moves on, so a release asked for against the old preview is refused as stale.
     */
    Run prepare(Run run) {
        var minimum = Paise.of(properties.minimumPaise());
        transactions.executeWithoutResult(status -> {
            var locked = jdbc.sql("select state from payouts.runs where id = :id for update")
                    .param("id", run.id())
                    .query(String.class)
                    .single();
            if ("released".equals(locked)) {
                throw new Refused("the batch is released; its preview doesn't change");
            }
            jdbc.sql("delete from payouts.batch_items where run_id = :id")
                    .param("id", run.id())
                    .update();
            int owners = 0;
            int included = 0;
            long total = 0;
            for (var userId : candidates()) {
                owners++;
                var quote = payouts.quote(userId, minimum);
                insert(run.id(), userId, quote);
                if (quote.leftOut() == null) {
                    included++;
                    total += quote.netPaise();
                }
            }
            jdbc.sql("""
                            update payouts.runs set finished_at = :now, owners = :owners, payouts = :payouts,
                                total_paise = :total, version = version + 1
                            where id = :id
                            """)
                    .param("now", Timestamp.from(now()))
                    .param("owners", owners)
                    .param("payouts", included)
                    .param("total", total)
                    .param("id", run.id())
                    .update();
        });
        return find(run.id()).orElseThrow();
    }

    /**
     * Releases a batch, as approved: once. Every included host's quote is taken again first, and if any differs from
     * the preview nothing is paid and the batch must be refreshed; so what's paid is what the approver saw. Then each is
     * paid as previewed, and a batch is released only once: running the same approval again finishes what it started,
     * and another approval is refused.
     */
    Run release(UUID runId, UUID approvalId, AuditTrail.Actor actor) {
        var minimum = Paise.of(properties.minimumPaise());
        transactions.executeWithoutResult(status -> {
            var run = jdbc.sql("select * from payouts.runs where id = :id for update")
                    .param("id", runId)
                    .query(PayoutRuns::run)
                    .optional()
                    .orElseThrow(() -> new Refused("there's no batch " + runId));
            if (run.released()) {
                var released = jdbc.sql("select approval_id from payouts.runs where id = :id")
                        .param("id", runId)
                        .query(UUID.class)
                        .optional();
                if (released.isPresent() && released.get().equals(approvalId)) {
                    return;
                }
                throw new Refused("the batch is already released");
            }
            for (var item : items(runId)) {
                var now = payouts.quote(item.userId(), minimum);
                if (!same(item.quote(), now)) {
                    throw new Refused("what " + item.userId() + " would be paid changed since the preview: refresh it");
                }
            }
            jdbc.sql("""
                            update payouts.runs set state = 'released', released_at = :now, released_by = :by,
                                approval_id = :approval, version = version + 1
                            where id = :id
                            """)
                    .param("now", Timestamp.from(now()))
                    .param("by", actor.label())
                    .param("approval", approvalId)
                    .param("id", runId)
                    .update();
            audit.record(new AuditTrail.Entry(
                    actor,
                    "payout_batch.release",
                    "payout_batch",
                    runId.toString(),
                    "released as approved",
                    null,
                    Map.of("payouts", run.payouts() == null ? 0 : run.payouts())));
        });
        String failure = null;
        try {
            for (var item : items(runId)) {
                if (item.quote().leftOut() != null) {
                    continue;
                }
                var payoutId = item.payoutId();
                if (payoutId == null) {
                    payoutId = paidIn(runId, item.userId()).orElse(null);
                }
                if (payoutId == null) {
                    var outcome = payouts.create(item.userId(), "weekly", runId, minimum);
                    if (outcome.payout() == null) {
                        log.warn("Batch {}: {} wasn't paid: {}", runId, item.userId(), outcome.notPaid());
                        continue;
                    }
                    payoutId = outcome.payout().id();
                }
                jdbc.sql("update payouts.batch_items set payout_id = :payout where run_id = :run and user_id = :user")
                        .param("payout", payoutId)
                        .param("run", runId)
                        .param("user", item.userId())
                        .update();
                payouts.send(payoutId);
            }
        } catch (RuntimeException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Payout batch {} stopped", runId, e);
        }
        if (failure != null) {
            jdbc.sql("update payouts.runs set failure = :failure where id = :id")
                    .param("failure", failure.substring(0, Math.min(500, failure.length())))
                    .param("id", runId)
                    .update();
        }
        return find(runId).orElseThrow();
    }

    /** Whether a quote now is what the preview said: the money and whether the person is paid. */
    private static boolean same(PayoutService.Quote preview, PayoutService.Quote now) {
        return preview.grossPaise() == now.grossPaise()
                && preview.tdsPaise() == now.tdsPaise()
                && preview.feePaise() == now.feePaise()
                && preview.netPaise() == now.netPaise()
                && preview.recoveredPaise() == now.recoveredPaise()
                && java.util.Objects.equals(preview.leftOut(), now.leftOut());
    }

    private Optional<UUID> paidIn(UUID runId, UUID userId) {
        return jdbc.sql("select id from payouts.payouts where run_id = :run and user_id = :user")
                .param("run", runId)
                .param("user", userId)
                .query(UUID.class)
                .optional();
    }

    private void insert(UUID runId, UUID userId, PayoutService.Quote quote) {
        jdbc.sql("""
                        insert into payouts.batch_items
                            (run_id, user_id, gross_paise, recovered_paise, held_paise, tds_paise, fee_paise, net_paise,
                             upi_masked, left_out)
                        values (:run, :user, :gross, :recovered, :held, :tds, :fee, :net, :upi, :leftOut)
                        """)
                .param("run", runId)
                .param("user", userId)
                .param("gross", quote.grossPaise())
                .param("recovered", quote.recoveredPaise())
                .param("held", quote.heldPaise())
                .param("tds", quote.tdsPaise())
                .param("fee", quote.feePaise())
                .param("net", quote.netPaise())
                .param("upi", quote.upiMasked())
                .param(
                        "leftOut",
                        quote.leftOut() == null ? null : quote.leftOut().name().toLowerCase(java.util.Locale.ROOT))
                .update();
    }

    /** A batch's hosts, as previewed, the paid ones first. */
    List<Item> items(UUID runId) {
        return jdbc.sql("""
                        select * from payouts.batch_items where run_id = :run
                        order by left_out nulls first, net_paise desc, user_id
                        """)
                .param("run", runId)
                .query((rs, n) -> {
                    var leftOut = rs.getString("left_out");
                    return new Item(
                            rs.getObject("run_id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            new PayoutService.Quote(
                                    rs.getLong("gross_paise"),
                                    rs.getLong("recovered_paise"),
                                    rs.getLong("held_paise"),
                                    rs.getLong("tds_paise"),
                                    rs.getLong("fee_paise"),
                                    rs.getLong("net_paise"),
                                    leftOut == null
                                            ? null
                                            : PayoutService.NotPaid.valueOf(leftOut.toUpperCase(java.util.Locale.ROOT)),
                                    rs.getString("upi_masked")),
                            rs.getObject("payout_id", UUID.class));
                })
                .list();
    }

    /** Pays one person at once (an admin's retry after a failure), above RazorpayX's minimum. */
    PayoutService.Outcome payNow(UUID userId, String kind, String reason, AuditTrail.Actor actor) {
        var outcome = payouts.create(userId, kind, null, RAZORPAYX_MINIMUM);
        if (outcome.payout() != null) {
            payouts.send(outcome.payout().id());
        }
        auditRetry(userId, outcome, reason, actor);
        return new PayoutService.Outcome(
                outcome.payout() == null
                        ? null
                        : payouts.find(outcome.payout().id()).orElseThrow(),
                outcome.notPaid());
    }

    private void auditRetry(UUID userId, PayoutService.Outcome outcome, String reason, AuditTrail.Actor actor) {
        transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                actor,
                "payout.retry",
                "user",
                userId.toString(),
                reason,
                null,
                outcome.payout() == null
                        ? Map.of("notPaid", String.valueOf(outcome.notPaid()))
                        : Map.of("payoutId", outcome.payout().id().toString()))));
    }

    /** A deletion's final payout: at once, outside the weekly run (ADR 0040). */
    PayoutService.Outcome payFinal(UUID userId) {
        var outcome = payouts.create(userId, "final", null, RAZORPAYX_MINIMUM);
        if (outcome.payout() != null) {
            payouts.send(outcome.payout().id());
        }
        return outcome;
    }

    /** Everyone with a UPI ID payouts may go to: the run asks each what's payable. */
    private List<UUID> candidates() {
        return jdbc.sql("select user_id from payouts.methods where state = 'active' order by user_id")
                .query(UUID.class)
                .list();
    }

    private Optional<Run> claim(String kind, LocalDate payday, String trigger) {
        var id = ids.newId();
        try {
            jdbc.sql("""
                            insert into payouts.runs (id, kind, payday, trigger, started_at, state)
                            values (:id, :kind, :payday, :trigger, :now, 'preview')
                            """)
                    .param("id", id)
                    .param("kind", kind)
                    .param("payday", payday)
                    .param("trigger", trigger)
                    .param("now", Timestamp.from(now()))
                    .update();
        } catch (DuplicateKeyException claimed) {
            return Optional.empty();
        }
        return find(id);
    }

    Optional<Run> find(UUID id) {
        return jdbc.sql("select * from payouts.runs where id = :id")
                .param("id", id)
                .query(PayoutRuns::run)
                .optional();
    }

    List<Run> latest(int limit) {
        return jdbc.sql("select * from payouts.runs order by started_at desc, id desc limit " + limit)
                .query(PayoutRuns::run)
                .list();
    }

    private static Run run(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var finished = rs.getTimestamp("finished_at");
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getString("kind"),
                rs.getObject("payday", LocalDate.class),
                rs.getString("trigger"),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getObject("owners", Integer.class),
                rs.getObject("payouts", Integer.class),
                rs.getObject("total_paise", Long.class),
                rs.getString("failure"),
                rs.getString("state"),
                rs.getLong("version"),
                rs.getTimestamp("released_at") == null
                        ? null
                        : rs.getTimestamp("released_at").toInstant(),
                rs.getString("released_by"));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
