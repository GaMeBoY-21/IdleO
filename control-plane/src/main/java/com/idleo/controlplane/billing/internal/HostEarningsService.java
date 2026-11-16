package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import com.idleo.controlplane.billing.HostEarnings;
import com.idleo.controlplane.billing.SessionCharges;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/** A host's earnings by session, summed from its {@code host_payable} ledger entries (ADR 0023). */
@Service
class HostEarningsService implements HostEarnings {

    private final JdbcClient jdbc;
    private final SessionCharges charges;
    private final OwnerEarningsQueries queries;
    private final Clock clock;
    private final CronExpression payoutCron;
    private final ZoneId payoutZone;
    private final boolean payoutsRunning;

    HostEarningsService(
            JdbcClient jdbc,
            SessionCharges charges,
            OwnerEarningsQueries queries,
            Clock clock,
            @Value("${payouts.weekly-cron}") String payoutCron,
            @Value("${payouts.zone}") String payoutZone,
            @Value("${payouts.razorpayx.key-id:}") String razorpayKeyId,
            @Value("${payouts.razorpayx.key-secret:}") String razorpayKeySecret,
            @Value("${payouts.razorpayx.account-number:}") String razorpayAccount) {
        this.jdbc = jdbc;
        this.charges = charges;
        this.queries = queries;
        this.clock = clock;
        this.payoutCron = CronExpression.parse(payoutCron);
        this.payoutZone = ZoneId.of(payoutZone);
        // The same test as the payouts module's: payouts run once a key, its secret and the account to pay from are
        // set.
        this.payoutsRunning = !razorpayKeyId.isBlank() && !razorpayKeySecret.isBlank() && !razorpayAccount.isBlank();
    }

    @Override
    public long earnedToday(UUID hostId) {
        var today = Instant.now(clock).atZone(OwnerEarningsController.IST).toLocalDate();
        return queries.between(
                List.of(hostId),
                today.atStartOfDay(OwnerEarningsController.IST).toInstant(),
                today.plusDays(1).atStartOfDay(OwnerEarningsController.IST).toInstant());
    }

    /** The IST week (Monday 00:00) and month (the 1st, 00:00) that contain a moment, as instants. */
    record Periods(Instant weekStart, Instant weekEnd, Instant monthStart, Instant monthEnd) {

        static Periods of(Instant now) {
            var ist = OwnerEarningsController.IST;
            var today = now.atZone(ist).toLocalDate();
            var week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            var month = today.withDayOfMonth(1);
            return new Periods(
                    week.atStartOfDay(ist).toInstant(),
                    week.plusDays(7).atStartOfDay(ist).toInstant(),
                    month.atStartOfDay(ist).toInstant(),
                    month.plusMonths(1).atStartOfDay(ist).toInstant());
        }
    }

    @Override
    public long earnedThisWeek(UUID hostId) {
        var periods = Periods.of(Instant.now(clock));
        return queries.between(List.of(hostId), periods.weekStart(), periods.weekEnd());
    }

    @Override
    public long earnedThisMonth(UUID hostId) {
        var periods = Periods.of(Instant.now(clock));
        return queries.between(List.of(hostId), periods.monthStart(), periods.monthEnd());
    }

    @Override
    public Optional<Payout> lastPayout(UUID hostId) {
        // A payout debits the host's payable account for what it took, in the payout's own transaction.
        return jdbc.sql("""
                        select created_at, amount_paise from billing.ledger_entries
                        where account = :account and owner_id = :hostId and ref_type = :ref and direction = 'debit'
                        order by created_at desc limit 1
                        """)
                .param("account", Account.HOST_PAYABLE.dbValue)
                .param("hostId", hostId)
                .param("ref", HostPayoutService.PAYOUT_REF)
                .query((rs, n) -> new Payout(rs.getTimestamp("created_at").toInstant(), rs.getLong("amount_paise")))
                .optional();
    }

    @Override
    public Optional<LocalDate> nextPayoutOn() {
        if (!payoutsRunning) {
            return Optional.empty();
        }
        var next = payoutCron.next(Instant.now(clock).atZone(payoutZone));
        return Optional.ofNullable(next).map(at -> at.toLocalDate());
    }

    @Override
    public Map<UUID, Earned> bySession(UUID hostId, Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        var earned = new HashMap<UUID, Earned>();
        jdbc.sql("""
                        select ref_id,
                               coalesce(sum(amount_paise) filter (where direction = 'credit'), 0) as credited,
                               coalesce(sum(amount_paise) filter (where direction = 'debit'), 0) as given_back
                        from billing.ledger_entries
                        where account = :account and owner_id = :hostId and ref_id in (:sessionIds)
                        group by ref_id
                        """)
                .param("account", Account.HOST_PAYABLE.dbValue)
                .param("hostId", hostId)
                .param("sessionIds", sessionIds)
                .query(rs -> {
                    earned.put(
                            rs.getObject("ref_id", UUID.class),
                            new Earned(rs.getLong("credited"), rs.getLong("given_back")));
                });
        return earned;
    }

    @Override
    public long shareOf(long chargedPaise) {
        return SessionCharges.split(Paise.of(chargedPaise), charges.commissionPercent())
                .hostShare()
                .value();
    }
}
