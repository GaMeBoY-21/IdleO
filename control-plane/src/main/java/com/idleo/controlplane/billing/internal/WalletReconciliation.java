package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.AuditTrail;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly proof that the ledger keeps its rules (ADR 0055): every one of the ledger's checks, the same functions
 * ops/backup/verify-ledger.sh reads after a restore ({@code billing.ledger_checks}, V78). Among them the wallet cache
 * equals the ledger, every credit lot what its entries leave in it (ADR 0039), the cached reservation the sum of the
 * active holds (ADR 0015), every hold its changes (ADR 0017), and every transaction and the whole ledger balance. It
 * reads in one read-only snapshot, so the database refuses anything it might write. One control-plane instance runs it
 * at a time, under a PostgreSQL advisory lock; the others skip. Each complete run is kept with every check's count and
 * every wallet, hold or lot that disagrees, for the admin console (ADR 0027), and an admin can run it at any time.
 * Mismatches are logged (user id and both values, nothing more) and counted in {@code
 * billing.reconciliation.mismatches}; {@code billing.ledger.checks.failing} and {@code billing.ledger.unbalanced} page
 * (ops/alerts/payments.yaml), and {@code billing.reconciliation.last_success} is when the last complete run finished.
 */
@Component
class WalletReconciliation {

    /** The advisory lock key: "billrecn" in ASCII. */
    static final long LOCK_KEY = 0x62696c6c7265636eL;

    private static final Logger log = LoggerFactory.getLogger(WalletReconciliation.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final AuditTrail audit;
    private final Clock clock;
    private final Counter mismatches;
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
    private final AtomicLong failingChecks = new AtomicLong();
    private final AtomicLong unbalanced = new AtomicLong();

    /** The checks that say the ledger itself doesn't balance: a transaction, or the whole. */
    private static final int UNBALANCED_CHECKS = 2;

    WalletReconciliation(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            IdGenerator ids,
            AuditTrail audit,
            Clock clock,
            MeterRegistry registry) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.snapshot = new TransactionTemplate(transactionManager);
        // One consistent view of wallets and ledger, while postings carry on.
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.clock = clock;
        this.mismatches = Counter.builder("billing.reconciliation.mismatches")
                .description("Wallets whose cached balance differs from the ledger, plus an unbalanced ledger")
                .register(registry);
        Gauge.builder("billing.reconciliation.last_success", lastSuccessEpochSeconds, AtomicLong::get)
                .description("When the last complete reconciliation finished, in Unix seconds")
                .register(registry);
        Gauge.builder("billing.ledger.checks.failing", failingChecks, AtomicLong::get)
                .description("How many of the ledger's checks found something in the last complete run")
                .register(registry);
        Gauge.builder("billing.ledger.unbalanced", unbalanced, AtomicLong::get)
                .description("1 when the last run found a transaction, or the whole ledger, that doesn't balance")
                .register(registry);
    }

    /**
     * What disagrees: the balance with the ledger, the reservation with the active holds, or a hold's amount with
     * its history of changes (ADR 0017; the user id is the hold's owner).
     */
    enum Kind {
        BALANCE(4),
        RESERVED(5),
        HOLD(6),
        LOT(8);

        /** Its check's position in {@code billing.ledger_check_names()}. */
        final int check;

        Kind(int check) {
            this.check = check;
        }

        static Kind ofCheck(int position) {
            for (var kind : values()) {
                if (kind.check == position) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("check " + position + " isn't about a wallet, hold or lot");
        }
    }

    record Mismatch(Kind kind, UUID userId, long walletPaise, long expectedPaise) {}

    /** One of the ledger's checks and how many findings it had: zero is a pass. */
    record Check(int position, String name, long found) {}

    /** {@code ran} is false when another instance held the lock; {@code runId} names the kept run once it's recorded. */
    record Result(
            boolean ran,
            List<Check> checks,
            List<Mismatch> mismatches,
            long ledgerDebitsPaise,
            long ledgerCreditsPaise,
            @Nullable UUID runId) {

        Result {
            checks = List.copyOf(checks);
            mismatches = List.copyOf(mismatches);
        }

        boolean ledgerBalanced() {
            return ledgerDebitsPaise == ledgerCreditsPaise;
        }
    }

    @Scheduled(cron = "${billing.reconciliation.cron}", zone = "${billing.reconciliation.zone}")
    void nightly() {
        run("nightly");
    }

    /**
     * Runs the check now for an operator's command or an admin (ADR 0027), the one method both call: what it found is
     * kept as every run's is, and the run is in the audit log, in the same transaction as its record.
     */
    Result run(AuditTrail.Actor actor, String reason) {
        return run(actor.label(), new Asked(actor, reason));
    }

    /** Who asked for a run, and why, for its audit row. */
    private record Asked(AuditTrail.Actor actor, String reason) {}

    /**
     * Runs the check now and keeps what it found in {@code billing.reconciliation_runs}, for the admin console;
     * {@code trigger} says who asked, {@code nightly} for the schedule.
     */
    Result run(String trigger) {
        return run(trigger, null);
    }

    private Result run(String trigger, @Nullable Asked asked) {
        var result = Objects.requireNonNull(snapshot.execute(status -> {
            Boolean locked = jdbc.sql("select pg_try_advisory_xact_lock(:key)")
                    .param("key", LOCK_KEY)
                    .query(Boolean.class)
                    .single();
            if (!Boolean.TRUE.equals(locked)) {
                return new Result(false, List.of(), List.of(), 0, 0, null);
            }
            // The ledger's checks (V78's billing.ledger_check_findings, which ops/backup/verify-ledger.sh reads too):
            // every check's count, and the findings of the four about wallets, holds and lots, one by one.
            var checks = jdbc.sql("select check_number, name, found from billing.ledger_checks()")
                    .query((rs, n) -> new Check(rs.getInt("check_number"), rs.getString("name"), rs.getLong("found")))
                    .list();
            var mismatches = jdbc.sql("""
                            select check_number, subject, actual, expected from billing.ledger_check_findings()
                            where check_number in (4, 5, 6, 8)
                            order by check_number, subject
                            """)
                    .query((rs, n) -> new Mismatch(
                            Kind.ofCheck(rs.getInt("check_number")),
                            rs.getObject("subject", UUID.class),
                            rs.getLong("actual"),
                            rs.getLong("expected")))
                    .list();
            var totals = jdbc.sql("""
                            select coalesce(sum(amount_paise) filter (where direction = 'debit'), 0) as debits,
                                   coalesce(sum(amount_paise) filter (where direction = 'credit'), 0) as credits
                            from billing.ledger_entries
                            """)
                    .query((rs, n) -> new long[] {rs.getLong("debits"), rs.getLong("credits")})
                    .single();
            return new Result(true, checks, mismatches, totals[0], totals[1], null);
        }));
        if (!result.ran()) {
            log.info("Wallet reconciliation skipped: another instance is running it");
            return result;
        }
        for (var mismatch : result.mismatches()) {
            log.error(
                    switch (mismatch.kind()) {
                        case BALANCE -> "Wallet {} disagrees with the ledger: wallet {} paise, ledger {} paise";
                        case RESERVED -> "Wallet {} reserves {} paise, but its active holds sum to {} paise";
                        case HOLD -> "A hold of {} has changes adding up to {} paise, but holds {} paise";
                        case LOT -> "A credit lot of {} keeps {} paise, but its entries leave {} paise";
                    },
                    mismatch.userId(),
                    mismatch.walletPaise(),
                    mismatch.expectedPaise());
        }
        for (var check : result.checks()) {
            if (check.found() > 0) {
                log.error("The ledger's check \"{}\" failed: {} findings", check.name(), check.found());
            }
        }
        // The run's mismatches are what it always counted: every wallet, hold and lot that disagrees, and an
        // unbalanced ledger. Every check's count is kept beside them, and pages through its own gauge.
        int problems = result.mismatches().size();
        if (!result.ledgerBalanced()) {
            log.error(
                    "The ledger doesn't balance: debits {} paise, credits {} paise",
                    result.ledgerDebitsPaise(),
                    result.ledgerCreditsPaise());
            problems++;
        }
        result = record(trigger, result, problems, asked);
        mismatches.increment(problems);
        failingChecks.set(
                result.checks().stream().filter(check -> check.found() > 0).count());
        unbalanced.set(
                result.checks().stream().anyMatch(check -> check.position() <= UNBALANCED_CHECKS && check.found() > 0)
                        ? 1
                        : 0);
        lastSuccessEpochSeconds.set(clock.instant().getEpochSecond());
        log.info("Wallet reconciliation finished: {} mismatches", problems);
        return result;
    }

    /**
     * The run and each mismatch, in their own transaction once the read-only snapshot has ended, with its audit row
     * when someone asked for it; the result names the run.
     */
    private Result record(String trigger, Result result, int problems, @Nullable Asked asked) {
        var runId = ids.newId();
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            insert into billing.reconciliation_runs
                                (id, trigger, finished_at, ledger_debits_paise, ledger_credits_paise, mismatches)
                            values (:id, :trigger, :now, :debits, :credits, :mismatches)
                            """)
                    .param("id", runId)
                    .param("trigger", trigger)
                    .param("now", java.sql.Timestamp.from(clock.instant()))
                    .param("debits", result.ledgerDebitsPaise())
                    .param("credits", result.ledgerCreditsPaise())
                    .param("mismatches", problems)
                    .update();
            for (var check : result.checks()) {
                jdbc.sql("""
                                insert into billing.reconciliation_checks (run_id, position, name, found)
                                values (:runId, :position, :name, :found)
                                """)
                        .param("runId", runId)
                        .param("position", check.position())
                        .param("name", check.name())
                        .param("found", check.found())
                        .update();
            }
            int position = 0;
            for (var mismatch : result.mismatches()) {
                jdbc.sql("""
                                insert into billing.reconciliation_mismatches
                                    (run_id, position, kind, user_id, wallet_paise, expected_paise)
                                values (:runId, :position, :kind, :userId, :wallet, :expected)
                                """)
                        .param("runId", runId)
                        .param("position", position++)
                        .param("kind", mismatch.kind().name().toLowerCase(java.util.Locale.ROOT))
                        .param("userId", mismatch.userId())
                        .param("wallet", mismatch.walletPaise())
                        .param("expected", mismatch.expectedPaise())
                        .update();
            }
            if (asked != null) {
                audit.record(new AuditTrail.Entry(
                        asked.actor(),
                        "reconciliation.run",
                        "reconciliation_run",
                        runId.toString(),
                        asked.reason(),
                        null,
                        Map.of("mismatches", problems, "ledgerBalanced", result.ledgerBalanced())));
            }
        });
        return new Result(
                true,
                result.checks(),
                result.mismatches(),
                result.ledgerDebitsPaise(),
                result.ledgerCreditsPaise(),
                runId);
    }
}
