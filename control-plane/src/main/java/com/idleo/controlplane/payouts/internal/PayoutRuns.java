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
 * Payout runs (ADR 0040): every Monday at 10:00 in India, everyone with a UPI ID payouts may go to and at least {@code
 * payouts.minimum-paise} available is paid, one payout each for all their machines. One instance claims the week's run
 * (a unique row a payday); a second one finds it claimed and does nothing. An admin may start a run at once, or pay one
 * person again after a payout failed; a deletion's final payout goes at once, whatever the amount above RazorpayX's
 * ₹1. There's no "pay me now" for hosts.
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
            @Nullable String failure) {}

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
        pay(run.get(), Paise.of(properties.minimumPaise()));
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

    /** Pays everyone who can be paid in the run; for an admin's run, after its row has committed. */
    Run pay(Run run, Paise minimum) {
        int owners = 0;
        int made = 0;
        long total = 0;
        String failure = null;
        try {
            for (var userId : candidates()) {
                owners++;
                var outcome = payouts.create(userId, "weekly", run.id(), minimum);
                if (outcome.payout() != null) {
                    made++;
                    total += outcome.payout().netPaise();
                    payouts.send(outcome.payout().id());
                } else {
                    log.debug("No payout for {}: {}", userId, outcome.notPaid());
                }
            }
        } catch (RuntimeException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Payout run {} stopped", run.id(), e);
        }
        jdbc.sql("""
                        update payouts.runs set finished_at = :now, owners = :owners, payouts = :payouts,
                            total_paise = :total, failure = :failure
                        where id = :id
                        """)
                .param("now", Timestamp.from(now()))
                .param("owners", owners)
                .param("payouts", made)
                .param("total", total)
                .param("failure", failure == null ? null : failure.substring(0, Math.min(500, failure.length())))
                .param("id", run.id())
                .update();
        return find(run.id()).orElseThrow();
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
                            insert into payouts.runs (id, kind, payday, trigger, started_at)
                            values (:id, :kind, :payday, :trigger, :now)
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
                rs.getString("failure"));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
