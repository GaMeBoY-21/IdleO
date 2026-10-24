package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.billing.HostPayouts;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The day before payday (ADR 0040): RazorpayX's available balance against what the next run would pay, everyone with a
 * UPI ID payouts may go to and at least the minimum payable. Too little means payouts will queue (RazorpayX's own
 * behaviour with {@code queue_if_low_balance}), so {@code payouts.balance.short} turns 1 and PayoutBalanceLow fires a
 * day ahead, while there's time to add funds.
 */
@Service
class BalanceCheck {

    private static final Logger log = LoggerFactory.getLogger(BalanceCheck.class);

    private final JdbcClient jdbc;
    private final RazorpayXClient razorpayx;
    private final HostPayouts ledger;
    private final PayoutRuns runs;
    private final PayoutsProperties properties;
    private final IdGenerator ids;
    private final Clock clock;
    private final PayoutsMetrics metrics;
    private final ZoneId zone;

    BalanceCheck(
            JdbcClient jdbc,
            RazorpayXClient razorpayx,
            HostPayouts ledger,
            PayoutRuns runs,
            PayoutsProperties properties,
            IdGenerator ids,
            Clock clock,
            PayoutsMetrics metrics,
            @Value("${payouts.zone}") String zone) {
        this.jdbc = jdbc;
        this.razorpayx = razorpayx;
        this.ledger = ledger;
        this.runs = runs;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
        this.metrics = metrics;
        this.zone = ZoneId.of(zone);
    }

    record Check(
            UUID id,
            LocalDate payday,
            Instant checkedAt,
            @Nullable Long balancePaise,
            long duePaise,
            boolean isShort,
            @Nullable String failure) {}

    @Scheduled(cron = "${payouts.balance-check-cron}", zone = "${payouts.zone}")
    void beforePayday() {
        if (razorpayx.enabled()) {
            check();
        }
    }

    /** Checks now, for the next payday. */
    Check check() {
        var payday = LocalDate.ofInstant(runs.nextPayday(), zone);
        long due = 0;
        for (var userId : jdbc.sql("select user_id from payouts.methods where state = 'active'")
                .query(UUID.class)
                .list()) {
            long payable = ledger.position(userId).payablePaise();
            if (payable >= properties.minimumPaise()) {
                due += payable;
            }
        }
        Long balance = null;
        String failure = null;
        try {
            balance = razorpayx.availableBalance().orElse(null);
            if (balance == null) {
                failure = "RazorpayX didn't list the account RAZORPAYX_ACCOUNT_NUMBER names";
            }
        } catch (RazorpayXClient.Refused | RazorpayXClient.Unreachable e) {
            failure = "RazorpayX couldn't be read: " + e.getMessage();
        }
        boolean isShort = balance == null || balance < due;
        var id = ids.newId();
        jdbc.sql("""
                        insert into payouts.balance_checks
                            (id, payday, checked_at, balance_paise, due_paise, short, failure)
                        values (:id, :payday, :now, :balance, :due, :short, :failure)
                        """)
                .param("id", id)
                .param("payday", payday)
                .param("now", Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)))
                .param("balance", balance)
                .param("due", due)
                .param("short", isShort)
                .param("failure", failure)
                .update();
        if (isShort) {
            log.warn("RazorpayX's balance {} can't cover {} due on {}: {}", balance, due, payday, failure);
        }
        metrics.balance(isShort, balance == null ? due : due - balance);
        return latest().orElseThrow();
    }

    /** Every instance's gauge, from the last check: short until a check finds enough, or payday has passed. */
    @Scheduled(fixedDelayString = "${payouts.reconciliation-count-every}")
    void gauge() {
        latest().ifPresentOrElse(
                        last -> {
                            boolean stillAhead =
                                    !LocalDate.ofInstant(clock.instant(), zone).isAfter(last.payday());
                            metrics.balance(
                                    last.isShort() && stillAhead,
                                    last.balancePaise() == null
                                            ? last.duePaise()
                                            : last.duePaise() - last.balancePaise());
                        },
                        () -> metrics.balance(false, 0));
    }

    Optional<Check> latest() {
        return jdbc.sql("select * from payouts.balance_checks order by checked_at desc, id desc limit 1")
                .query((rs, n) -> new Check(
                        rs.getObject("id", UUID.class),
                        rs.getObject("payday", LocalDate.class),
                        rs.getTimestamp("checked_at").toInstant(),
                        rs.getObject("balance_paise", Long.class),
                        rs.getLong("due_paise"),
                        rs.getBoolean("short"),
                        rs.getString("failure")))
                .optional();
    }
}
